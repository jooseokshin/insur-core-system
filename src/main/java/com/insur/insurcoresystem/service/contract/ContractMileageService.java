package com.insur.insurcoresystem.service.contract;

import static com.insur.insurcoresystem.common.validation.InputRules.isInvalidCode;
import static com.insur.insurcoresystem.service.contract.ContractSupport.isReadable;
import static com.insur.insurcoresystem.service.contract.ContractSupport.normalizeEmpId;
import static com.insur.insurcoresystem.service.contract.ContractSupport.validateGlobalTxId;

import com.insur.insurcoresystem.common.exception.BusinessException;
import com.insur.insurcoresystem.domain.contract.Contract;
import com.insur.insurcoresystem.domain.contract.ContractMileage;
import com.insur.insurcoresystem.domain.contract.ContractMileageRepository;
import com.insur.insurcoresystem.domain.contract.ContractRepository;
import com.insur.insurcoresystem.domain.contract.ContractStatus;
import com.insur.insurcoresystem.service.contract.dto.MileageRequest;
import com.insur.insurcoresystem.service.contract.dto.MileageResponse;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 자동차 마일리지 특약. 시작 주행거리를 등록하고, 만기 무렵 만기 주행거리로 정산해 환급액을 정한다.
 * <ul>
 *     <li>등록: 청약(10)·유효(20) 자동차 계약, 보험시기 + {@value #REGISTER_DAYS}일까지, 계약당 한 번</li>
 *     <li>정산: 유효(20) 계약, 보험종기 − {@value #SETTLE_BEFORE_DAYS}일 ~ 보험종기 + {@value #SETTLE_AFTER_DAYS}일, 만기 주행거리 ≥ 시작</li>
 *     <li>1년 환산 주행거리 = 실제 주행거리 × 365 ÷ 보험기간 일수 (반올림). 보험기간이 1년이 아니어도 같은 구간표를 쓰기 위해서다</li>
 *     <li>환급률은 보험시기에 적용되는 구간표(TB_PRM_MILEAGE_RATE)에서 찾고, 구간이 없으면(먼 거리) 0</li>
 *     <li>환급액 = 계약 총보험료 × 환급률, 원 미만 버림. 실제 지급(송금)은 이 기간계 범위 밖이고 금액만 확정한다</li>
 * </ul>
 * 등록·정산은 각각 거래 UUID 단위로 한 번만 반영되고, 같은 UUID 재전송은 현재 상태를 돌려준다. 계약 행을 잠가 같은 계약의 동시 처리를 막는다.
 * 설계사·채널 시스템은 자기가 접수한 계약만 다룬다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContractMileageService {

    static final int REGISTER_DAYS = 30;
    static final int SETTLE_BEFORE_DAYS = 30;
    static final int SETTLE_AFTER_DAYS = 60;
    static final int MAX_ODOMETER_KM = 9_999_999;

    private static final String RATE_SQL = """
            SELECT REFUND_RATE FROM TB_PRM_MILEAGE_RATE
             WHERE :annualKm BETWEEN KM_FROM AND KM_TO
               AND EFF_FROM <= :startDate AND (EFF_TO IS NULL OR EFF_TO >= :startDate)
            """;

    private final ContractRepository contractRepository;
    private final ContractMileageRepository mileageRepository;
    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;

    /**
     * 시작 주행거리를 등록한다 (마일리지 특약 가입).
     *
     * @param ownerEmpId 접수자 본인의 계약으로 제한할 사번(설계사·채널 시스템). null 이면 제한 없음
     * @throws BusinessException 거래 UUID 오류(E-CTR-008), 증권번호 누락(E-CTR-009), 주행거리 오류(E-MIL-001), 계약 없음(E-CTR-010),
     *                           자동차가 아니거나 등록할 수 없는 상태(E-MIL-002), 등록 기간 지남(E-MIL-003), 이미 등록(E-MIL-004),
     *                           같은 거래 UUID 로 다른 내용(E-CTR-019)
     */
    @Transactional
    public MileageResponse register(String ownerEmpId, String globalTxId, String empId, MileageRequest request) {
        validate(globalTxId, request);
        var replay = mileageRepository.findByStartTxId(globalTxId);
        if (replay.isPresent()) {
            ContractMileage done = replay.get();
            requireSameRequest(done.getContractNo(), done.getStartOdoKm(), request);
            return response(lockedContract(done.getContractNo(), ownerEmpId), done);
        }
        Contract contract = lockedContract(request.getContractNo(), ownerEmpId);
        requireCar(contract, Set.of(ContractStatus.APPLIED.getCode(), ContractStatus.IN_FORCE.getCode()));
        LocalDate today = LocalDate.now(clock);
        LocalDate until = contract.getStartDate().plusDays(REGISTER_DAYS);
        if (today.isAfter(until)) {
            throw new BusinessException(HttpStatus.CONFLICT, "E-MIL-003", "시작 주행거리는 보험시기 " + REGISTER_DAYS
                    + "일 뒤(" + until + ")까지만 등록할 수 있습니다.");
        }
        if (mileageRepository.existsById(contract.getContractNo())) {
            throw new BusinessException(HttpStatus.CONFLICT, "E-MIL-004", "이미 마일리지 특약에 가입한 계약입니다.");
        }
        ContractMileage saved = mileageRepository.saveAndFlush(ContractMileage.builder()
                .contractNo(contract.getContractNo())
                .milStatCd(ContractMileage.STAT_REGISTERED)
                .startOdoKm(request.getOdometerKm())
                .startRegDt(LocalDateTime.now(clock))
                .startEmpId(normalizeEmpId(empId))
                .startTxId(globalTxId)
                .build());
        log.info("마일리지 시작 등록: 증권번호={}, 주행거리={}km", contract.getContractNo(), request.getOdometerKm());
        return response(contract, saved);
    }

    /**
     * 만기 주행거리로 정산해 환급액을 정한다.
     *
     * @throws BusinessException 입력 오류(E-CTR-008, 009, E-MIL-001), 계약 없음(E-CTR-010), 자동차가 아니거나 유효 계약이 아님(E-MIL-002),
     *                           정산 기간이 아님(E-MIL-003), 가입하지 않았거나 이미 정산함(E-MIL-004),
     *                           만기 주행거리가 시작보다 작음(E-MIL-005), 같은 거래 UUID 로 다른 내용(E-CTR-019)
     */
    @Transactional
    public MileageResponse settle(String ownerEmpId, String globalTxId, String empId, MileageRequest request) {
        validate(globalTxId, request);
        var replay = mileageRepository.findByEndTxId(globalTxId);
        if (replay.isPresent()) {
            ContractMileage done = replay.get();
            requireSameRequest(done.getContractNo(), done.getEndOdoKm(), request);
            return response(lockedContract(done.getContractNo(), ownerEmpId), done);
        }
        Contract contract = lockedContract(request.getContractNo(), ownerEmpId);
        requireCar(contract, Set.of(ContractStatus.IN_FORCE.getCode()));
        ContractMileage mileage = mileageRepository.findById(contract.getContractNo())
                .filter(m -> ContractMileage.STAT_REGISTERED.equals(m.getMilStatCd()))
                .orElseThrow(() -> new BusinessException(HttpStatus.CONFLICT, "E-MIL-004",
                        "시작 주행거리를 등록하지 않았거나 이미 정산한 계약입니다."));
        LocalDate today = LocalDate.now(clock);
        LocalDate from = contract.getEndDate().minusDays(SETTLE_BEFORE_DAYS);
        LocalDate until = contract.getEndDate().plusDays(SETTLE_AFTER_DAYS);
        if (today.isBefore(from) || today.isAfter(until)) {
            throw new BusinessException(HttpStatus.CONFLICT, "E-MIL-003", "정산은 " + from + " ~ " + until + " 에만 할 수 있습니다.");
        }
        if (request.getOdometerKm() < mileage.getStartOdoKm()) {
            throw new BusinessException("E-MIL-005", "만기 주행거리(" + request.getOdometerKm() + "km)가 시작 주행거리("
                    + mileage.getStartOdoKm() + "km)보다 작습니다.");
        }

        int driven = request.getOdometerKm() - mileage.getStartOdoKm();
        long termDays = ChronoUnit.DAYS.between(contract.getStartDate(), contract.getEndDate());
        int annual = BigDecimal.valueOf(driven).multiply(BigDecimal.valueOf(365))
                .divide(BigDecimal.valueOf(termDays), 0, RoundingMode.HALF_UP).intValueExact();
        BigDecimal rate = jdbc.query(RATE_SQL, new MapSqlParameterSource()
                        .addValue("annualKm", annual).addValue("startDate", contract.getStartDate()),
                (rs, i) -> rs.getBigDecimal("REFUND_RATE")).stream().findFirst().orElse(BigDecimal.ZERO);
        BigDecimal base = contract.getTotalPremium();
        BigDecimal refund = base.multiply(rate).setScale(0, RoundingMode.DOWN);

        mileage.settle(request.getOdometerKm(), driven, annual, rate, base, refund, normalizeEmpId(empId), globalTxId,
                LocalDateTime.now(clock));
        mileageRepository.saveAndFlush(mileage);
        log.info("마일리지 정산: 증권번호={}, 주행 {}km(1년 환산 {}km), 환급률 {}, 환급액 {}", contract.getContractNo(), driven, annual, rate, refund);
        return response(contract, mileage);
    }

    /**
     * 마일리지 특약 현황. 가입하지 않았으면 등록·정산 가능 기간만 채워서 돌려준다.
     *
     * @throws BusinessException 증권번호 누락(E-CTR-009), 계약 없음(E-CTR-010), 자동차 계약이 아님(E-MIL-002)
     */
    @Transactional(readOnly = true)
    public MileageResponse inquiry(String ownerEmpId, String contractNo) {
        if (isInvalidCode(contractNo, 30)) {
            throw new BusinessException("E-CTR-009", "증권번호를 입력해야 합니다.");
        }
        Contract contract = contractRepository.findById(contractNo)
                .filter(found -> isReadable(found, ownerEmpId))
                .orElseThrow(ContractSupport::contractNotFound);
        if (!Contract.INSR_CLS_CAR.equals(contract.getInsrClsCd())) {
            throw new BusinessException(HttpStatus.CONFLICT, "E-MIL-002", "마일리지 특약은 자동차 계약에만 있습니다.");
        }
        return response(contract, mileageRepository.findById(contractNo).orElse(null));
    }

    private static void validate(String globalTxId, MileageRequest request) {
        validateGlobalTxId(globalTxId);
        if (isInvalidCode(request.getContractNo(), 30)) {
            throw new BusinessException("E-CTR-009", "증권번호를 입력해야 합니다.");
        }
        if (request.getOdometerKm() == null || request.getOdometerKm() < 0 || request.getOdometerKm() > MAX_ODOMETER_KM) {
            throw new BusinessException("E-MIL-001", "주행거리는 0 이상 " + MAX_ODOMETER_KM + "km 이하의 정수여야 합니다.");
        }
    }

    /** 같은 거래 UUID 의 재전송이 처음과 같은 계약·주행거리인지 */
    private static void requireSameRequest(String contractNo, Integer odometerKm, MileageRequest request) {
        if (!contractNo.equals(request.getContractNo()) || !Objects.equals(odometerKm, request.getOdometerKm())) {
            throw new BusinessException(HttpStatus.CONFLICT, "E-CTR-019",
                    "같은 거래 UUID(globalTxId)로 이전과 다른 내용의 요청이 들어왔습니다. 새 거래 UUID 로 다시 요청해 주세요.");
        }
    }

    /** 계약 행을 잠가 읽는다 (같은 계약의 등록·정산이 동시에 반영되지 않게). 범위 밖이면 없는 계약과 같은 오류 */
    private Contract lockedContract(String contractNo, String ownerEmpId) {
        return contractRepository.findForUpdateByContractNo(contractNo)
                .filter(found -> isReadable(found, ownerEmpId))
                .orElseThrow(ContractSupport::contractNotFound);
    }

    private static void requireCar(Contract contract, Set<String> allowedStatuses) {
        if (!Contract.INSR_CLS_CAR.equals(contract.getInsrClsCd())) {
            throw new BusinessException(HttpStatus.CONFLICT, "E-MIL-002", "마일리지 특약은 자동차 계약에만 있습니다.");
        }
        if (!allowedStatuses.contains(contract.getCtrStatCd())) {
            throw new BusinessException(HttpStatus.CONFLICT, "E-MIL-002",
                    "지금 계약 상태(" + contract.getCtrStatCd() + ")에서는 처리할 수 없습니다.");
        }
    }

    private static MileageResponse response(Contract contract, ContractMileage mileage) {
        MileageResponse.MileageResponseBuilder builder = MileageResponse.builder()
                .contractNo(contract.getContractNo())
                .registerUntil(contract.getStartDate().plusDays(REGISTER_DAYS))
                .settleFrom(contract.getEndDate().minusDays(SETTLE_BEFORE_DAYS))
                .settleUntil(contract.getEndDate().plusDays(SETTLE_AFTER_DAYS));
        if (mileage != null) {
            builder.milStatCd(mileage.getMilStatCd())
                    .startOdometerKm(mileage.getStartOdoKm()).startRegDt(mileage.getStartRegDt())
                    .endOdometerKm(mileage.getEndOdoKm()).endRegDt(mileage.getEndRegDt())
                    .drivenKm(mileage.getDrivenKm()).annualKm(mileage.getAnnualKm())
                    .refundRate(mileage.getRefundRate()).refundBasePrem(mileage.getRefundBasePrem()).refundAmt(mileage.getRefundAmt());
        }
        return builder.build();
    }
}

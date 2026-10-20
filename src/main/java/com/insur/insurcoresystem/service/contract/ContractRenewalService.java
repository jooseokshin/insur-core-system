package com.insur.insurcoresystem.service.contract;

import static com.insur.insurcoresystem.common.validation.InputRules.isInvalidCode;
import static com.insur.insurcoresystem.service.contract.ContractSupport.isReadable;
import static com.insur.insurcoresystem.service.contract.ContractSupport.normalizeEmpId;
import static com.insur.insurcoresystem.service.contract.ContractSupport.validateGlobalTxId;

import com.insur.insurcoresystem.common.exception.BusinessException;
import com.insur.insurcoresystem.common.web.LogSanitizer;
import com.insur.insurcoresystem.domain.contract.Contract;
import com.insur.insurcoresystem.domain.contract.ContractRepository;
import com.insur.insurcoresystem.domain.contract.ContractStatus;
import com.insur.insurcoresystem.domain.contract.Coverage;
import com.insur.insurcoresystem.domain.contract.CoverageRepository;
import com.insur.insurcoresystem.service.contract.dto.ContractApplyRequest;
import com.insur.insurcoresystem.service.contract.dto.ContractApplyResponse;
import com.insur.insurcoresystem.service.contract.dto.ContractRenewPreviewResponse;
import com.insur.insurcoresystem.service.contract.dto.ContractRenewRequest;
import com.insur.insurcoresystem.service.contract.dto.ContractRenewResponse;
import com.insur.insurcoresystem.service.contract.dto.CoverageRequest;
import com.insur.insurcoresystem.service.premium.PremiumService;
import com.insur.insurcoresystem.service.premium.dto.PremiumQuoteRequest;
import com.insur.insurcoresystem.service.premium.dto.PremiumQuoteResponse;
import com.insur.insurcoresystem.service.premium.dto.RatingInput;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.Period;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 계약 갱신 서비스. 유효한 계약의 만기 전에 같은 조건의 새 청약을 만들고, 보험료는 갱신 시점의 요율표와 필수 계수 정책으로 다시 산출한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContractRenewalService {

    /** 보험종기 며칠 전부터 갱신할 수 있는지 */
    public static final int RENEWAL_WINDOW_DAYS = 60;

    private final ContractRepository contractRepository;
    private final CoverageRepository coverageRepository;
    private final ContractService contractService;
    private final PremiumService premiumService;
    /** 업무 시간대(Asia/Seoul) 기준 현재 시각 */
    private final Clock clock;

    /**
     * 유효(20) 계약을 갱신해 새 청약을 접수한다.
     * <ul>
     *     <li>갱신 계약의 보험시기는 원 계약의 보험종기이고, 보험기간 길이는 원 계약과 같다(년/월 단위면 같은 년/월, 아니면 같은 일수).</li>
     *     <li>계약자, 피보험자, 담보와 가입금액을 이어받고 담보 보험료는 갱신 보험기간 기준으로 다시 산출한다.</li>
     *     <li>요율 계수는 요청의 rating, 없으면 원 계약에 저장된 값을 쓴다.</li>
     *     <li>보험종기 {@value #RENEWAL_WINDOW_DAYS}일 전부터 보험종기까지(서울 기준) 갱신할 수 있고, 계약당 갱신은 하나만 허용한다
     *         (갱신 계약이 청약취소되면 다시 갱신할 수 있다).</li>
     *     <li>같은 거래 UUID 의 재전송은 요율이 바뀌었어도 처음 결과를 돌려주고, 다른 원 계약이면 거절한다.</li>
     * </ul>
     *
     * @param ownerEmpId 갱신을 접수자 본인의 계약으로 제한할 사번. null 이면 제한 없이 갱신한다
     * @param globalTxId 거래 UUID (멱등성 키)
     * @param empId      처리 사번 (갱신 계약의 접수 사번)
     * @param request    원 계약 증권번호, 요율 계수(선택)
     * @return 갱신 계약의 번호와 기간, 원 계약과 갱신 계약의 총보험료
     * @throws BusinessException 입력 오류(E-CTR-008, E-CTR-009), 계약 없음 또는 권한 밖(E-CTR-010),
     *                           유효 상태가 아님(E-CTR-023), 갱신 가능 기간이 아님(E-CTR-024), 이미 갱신됨(E-CTR-025),
     *                           다른 사용자의 거래 UUID(E-CTR-015), 같은 거래 UUID 로 다른 원 계약(E-CTR-019),
     *                           보험료 산출 오류(E-PRM-001~005)
     */
    @Transactional
    public ContractRenewResponse renew(String ownerEmpId, String globalTxId, String empId, ContractRenewRequest request) {
        validateGlobalTxId(globalTxId);
        if (isInvalidCode(request.getContractNo(), 30)) {
            throw new BusinessException("E-CTR-009", "갱신할 계약의 증권번호를 입력해야 합니다.");
        }

        var replay = contractRepository.findByGlobalTxId(globalTxId);
        if (replay.isPresent()) {
            return replayResponse(replay.get(), empId, request.getContractNo());
        }

        Plan plan = plan(ownerEmpId, request);
        Contract source = plan.source();
        LocalDate startDate = plan.startDate();
        LocalDate endDate = plan.endDate();
        RatingInput rating = plan.rating();
        PremiumQuoteResponse quote = plan.quote();

        ContractApplyResponse applied = contractService.applyContract(globalTxId, empId, ContractApplyRequest.builder()
                .insrClsCd(source.getInsrClsCd()).startDate(startDate).endDate(endDate)
                .policyholderId(source.getPolicyholderId()).insuredId(source.getInsuredId())
                .totalPremium(quote.getTotalPremium()).rating(rating)
                .coverages(quote.getCoverages().stream().map(c -> CoverageRequest.builder()
                        .cvrgCd(c.getCvrgCd()).isrdAmt(c.getIsrdAmt()).prem(c.getPrem()).build()).toList())
                .build(), source.getContractNo());

        log.info("계약 갱신 접수: 원 계약={}, 갱신 계약={}, 총보험료 {} -> {}", source.getContractNo(), applied.getContractNo(),
                source.getTotalPremium(), applied.getTotalPremium());
        return ContractRenewResponse.builder()
                .contractNo(applied.getContractNo()).applNo(applied.getApplNo()).ctrStatCd(applied.getCtrStatCd())
                .renewedFrom(source.getContractNo()).startDate(startDate).endDate(endDate)
                .previousTotalPremium(source.getTotalPremium()).totalPremium(applied.getTotalPremium())
                .coverageCount(applied.getCoverageCount())
                .build();
    }

    /**
     * 갱신하면 어떻게 되는지 미리 보여 준다. 아무것도 저장하지 않으며 갱신과 같은 검증(상태, 기간, 이미 갱신 여부, 접수자 제한)과
     * 같은 산출을 거치므로, 이어서 같은 요청으로 갱신하면 같은 보험료가 된다(그 사이 요율이 바뀌지 않았다면).
     *
     * @param ownerEmpId 미리보기를 접수자 본인의 계약으로 제한할 사번. null 이면 제한 없음
     * @param request    원 계약 증권번호, 요율 계수(선택)
     * @return 갱신 보험기간, 원 계약 대비 담보별/총 보험료, 적용한 계수
     * @throws BusinessException 갱신과 같음 (거래 UUID 관련 오류만 없다)
     */
    @Transactional(readOnly = true)
    public ContractRenewPreviewResponse preview(String ownerEmpId, ContractRenewRequest request) {
        if (isInvalidCode(request.getContractNo(), 30)) {
            throw new BusinessException("E-CTR-009", "갱신할 계약의 증권번호를 입력해야 합니다.");
        }
        Plan plan = plan(ownerEmpId, request);
        Contract source = plan.source();
        PremiumQuoteResponse quote = plan.quote();
        return ContractRenewPreviewResponse.builder()
                .renewedFrom(source.getContractNo()).startDate(plan.startDate()).endDate(plan.endDate())
                .previousTotalPremium(source.getTotalPremium()).totalPremium(quote.getTotalPremium())
                .premiumDifference(quote.getTotalPremium().subtract(source.getTotalPremium()))
                .combinedFactor(quote.getCombinedFactor()).requiredFactors(quote.getRequiredFactors())
                .coverages(quote.getCoverages().stream().map(c -> ContractRenewPreviewResponse.Item.builder()
                        .cvrgCd(c.getCvrgCd()).isrdAmt(c.getIsrdAmt())
                        .previousPrem(plan.previousPrem().get(c.getCvrgCd())).prem(c.getPrem()).build()).toList())
                .build();
    }

    /** 갱신 계획: 원 계약 확인, 갱신 보험기간 계산, 갱신 시점 요율로 보험료 산출. 갱신과 미리보기가 함께 쓴다. */
    private Plan plan(String ownerEmpId, ContractRenewRequest request) {
        Contract source = contractRepository.findById(request.getContractNo())
                .filter(found -> isReadable(found, ownerEmpId))
                .orElseThrow(ContractSupport::contractNotFound);
        validateRenewable(source);

        LocalDate startDate = source.getEndDate();
        // 년/월 단위로 딱 떨어지는 기간은 같은 길이(윤년에도 1년)로, 아니면 같은 일수로 잇는다 (예: 2/29 시작 1년 미만 기간)
        Period term = Period.between(source.getStartDate(), source.getEndDate());
        LocalDate endDate = term.getDays() == 0 ? startDate.plus(term)
                : startDate.plusDays(ChronoUnit.DAYS.between(source.getStartDate(), source.getEndDate()));
        RatingInput rating = request.getRating() != null ? request.getRating() : storedRating(source);
        List<Coverage> sourceCoverages = coverageRepository.findByContractContractNo(source.getContractNo());

        PremiumQuoteResponse quote = premiumService.quote(PremiumQuoteRequest.builder()
                .insrClsCd(source.getInsrClsCd()).startDate(startDate).endDate(endDate).rating(rating)
                .coverages(sourceCoverages.stream().map(c -> PremiumQuoteRequest.QuoteCoverageRequest.builder()
                        .cvrgCd(c.getCvrgCd()).isrdAmt(c.getIsrdAmt()).build()).toList())
                .build());
        Map<String, BigDecimal> previousPrem = sourceCoverages.stream()
                .collect(Collectors.toMap(Coverage::getCvrgCd, Coverage::getPrem));
        return new Plan(source, startDate, endDate, rating, quote, previousPrem);
    }

    /** 갱신 계획 결과 */
    private record Plan(Contract source, LocalDate startDate, LocalDate endDate, RatingInput rating,
            PremiumQuoteResponse quote, Map<String, BigDecimal> previousPrem) {
    }

    /** 이미 처리한 거래 UUID 의 재전송이면 처음 갱신 결과를 만들어 돌려준다. 사번과 원 계약이 처음과 같아야 한다. */
    private ContractRenewResponse replayResponse(Contract renewal, String empId, String requestedSource) {
        if (renewal.getRegEmpId() != null && !renewal.getRegEmpId().equals(normalizeEmpId(empId))) {
            log.warn("다른 사용자가 접수한 거래 UUID 로 갱신 재전송이 들어왔다: 접수 사번={}, 요청 사번={}",
                    LogSanitizer.clean(renewal.getRegEmpId()), LogSanitizer.clean(empId));
            throw new BusinessException(HttpStatus.CONFLICT, "E-CTR-015", "이미 다른 사용자가 사용한 거래 UUID(globalTxId)입니다.");
        }
        if (renewal.getRenewedFrom() == null || !renewal.getRenewedFrom().equals(requestedSource)) {
            throw new BusinessException(HttpStatus.CONFLICT, "E-CTR-019",
                    "같은 거래 UUID(globalTxId)로 이전과 다른 내용의 요청이 들어왔습니다. 새 거래 UUID 로 다시 요청해 주세요.");
        }
        Contract source = contractRepository.findById(renewal.getRenewedFrom()).orElseThrow(ContractSupport::contractNotFound);
        return ContractRenewResponse.builder()
                .contractNo(renewal.getContractNo()).applNo(renewal.getApplNo()).ctrStatCd(renewal.getCtrStatCd())
                .renewedFrom(source.getContractNo()).startDate(renewal.getStartDate()).endDate(renewal.getEndDate())
                .previousTotalPremium(source.getTotalPremium()).totalPremium(renewal.getTotalPremium())
                .coverageCount(coverageRepository.findByContractContractNo(renewal.getContractNo()).size())
                .build();
    }

    /** 갱신할 수 있는 상태·기간인지, 이미 갱신하지 않았는지 확인한다. */
    private void validateRenewable(Contract source) {
        if (!ContractStatus.IN_FORCE.getCode().equals(source.getCtrStatCd())) {
            throw new BusinessException(HttpStatus.CONFLICT, "E-CTR-023", "유효(20) 상태의 계약만 갱신할 수 있습니다.");
        }
        LocalDate today = LocalDate.now(clock);
        if (today.isBefore(source.getEndDate().minusDays(RENEWAL_WINDOW_DAYS)) || today.isAfter(source.getEndDate())) {
            throw new BusinessException(HttpStatus.CONFLICT, "E-CTR-024",
                    "보험종기 " + RENEWAL_WINDOW_DAYS + "일 전부터 보험종기까지만 갱신할 수 있습니다. (보험종기 " + source.getEndDate() + ")");
        }
        if (contractRepository.findFirstByRenewedFromAndCtrStatCdNot(source.getContractNo(), ContractStatus.CANCELED.getCode()).isPresent()) {
            throw new BusinessException(HttpStatus.CONFLICT, "E-CTR-025", "이미 갱신된 계약입니다.");
        }
    }

    /** 원 계약에 저장된 요율 계수 입력값. 하나도 없으면 null */
    private static RatingInput storedRating(Contract source) {
        if (source.getDriverAge() == null && source.getDiscountGrade() == null && source.getStructureGrade() == null) {
            return null;
        }
        return RatingInput.builder().driverAge(source.getDriverAge()).discountGrade(source.getDiscountGrade())
                .structureGrade(source.getStructureGrade()).build();
    }
}

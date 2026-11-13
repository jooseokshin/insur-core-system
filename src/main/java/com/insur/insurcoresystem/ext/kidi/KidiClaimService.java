package com.insur.insurcoresystem.ext.kidi;

import static com.insur.insurcoresystem.common.validation.InputRules.isInvalidCode;

import com.insur.insurcoresystem.common.exception.BusinessException;
import com.insur.insurcoresystem.service.claim.ClaimService;
import com.insur.insurcoresystem.service.claim.dto.ClaimRequests;
import com.insur.insurcoresystem.service.claim.dto.ClaimResponses;
import com.insur.insurcoresystem.service.medical.MedicalClaimService;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 보험개발원 실손 청구 전산화(실손24) 수신.
 * <p>
 * 2024년 10월(병원급)부터 보험업법에 따라 환자가 요청하면 의료기관이 실손 청구 서류를 전자로 보내고, 보험개발원이 중계한다. 보험회사는 서류를
 * 종이·사진으로 받아 다시 입력하던 일이 없어지는 대신, 중계에서 오는 청구를 받아 사고로 접수할 수 있어야 한다. 이 클래스는 받은 진료 내역으로
 * 피보험자의 실손 계약을 찾아 보통의 실손 사고(진료 내역 경로 KIDI)로 접수하고, 손해사정부터는 직원 접수와 똑같이 처리한다.
 * <ul>
 *     <li>멱등성: 중계 전송번호로 한 번만 접수한다 (사고의 거래 UUID = {@code KIDI-} + 전송번호). 중계가 응답을 못 받고 다시 보내도 같은 사고를 돌려준다.</li>
 *     <li>계약 찾기: 피보험자의 실손 계약 중 첫 진료일을 보장하는 유효·해지 계약. 없으면 E-KIDI-002, 둘 이상이면 자동으로 고르지 않고 E-KIDI-003
 *         (담당자 확인). 단체·개인 실손이 함께 있는 경우 등은 실무에서 사람이 판단한다.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KidiClaimService {

    private static final String TX_PREFIX = "KIDI-";

    private final ClaimService claimService;
    private final NamedParameterJdbcTemplate jdbc;

    /**
     * 청구를 접수한다.
     *
     * @param clientId 중계 시스템의 클라이언트 ID (접수자로 남긴다)
     * @throws BusinessException 입력 오류(E-KIDI-001), 보장하는 계약 없음(E-KIDI-002, 404), 계약이 여럿(E-KIDI-003, 409),
     *                           진료 내역 오류(E-MED-005, E-CLM-005), 같은 전송번호로 다른 내용(E-CLM-009, 409)
     */
    @Transactional
    public KidiClaimResponse receive(String clientId, KidiClaimRequest request) {
        if (isInvalidCode(request.getTransmissionId(), 30) || request.getHospitalCode() == null || !request.getHospitalCode().matches("\\d{8}")
                || isInvalidCode(request.getHospitalName(), 80) || isInvalidCode(request.getInsuredId(), 20)
                || request.getTreatments() == null || request.getTreatments().isEmpty()) {
            throw new BusinessException("E-KIDI-001", "전송번호(30자 이하), 요양기관기호(8자리), 의료기관명, 피보험자, 진료 내역은 필수입니다.");
        }
        List<ClaimRequests.MedicalItem> items = request.getTreatments().stream()
                .map(t -> t == null ? null : ClaimRequests.MedicalItem.builder()
                        .treatDt(t.getTreatDt()).careType(t.getCareType()).facilityType(request.getFacilityType())
                        .facilityNm(request.getHospitalName()).coveredAmt(t.getCoveredAmt()).nonCoveredAmt(t.getNonCoveredAmt()).build())
                .toList();
        MedicalClaimService.validateItems(items);
        LocalDate first = MedicalClaimService.firstTreatDate(items);

        List<String> contracts = jdbc.queryForList("""
                SELECT CONTRACT_NO FROM TB_CTR_LGD
                 WHERE INSURED_ID = :insuredId AND INSR_CLS_CD = '05' AND CTR_STAT_CD IN ('20', '30')
                   AND START_DATE <= :first AND :first < LEAST(END_DATE, COALESCE(TERM_DT, END_DATE))
                 ORDER BY CONTRACT_NO
                """, new MapSqlParameterSource("insuredId", request.getInsuredId()).addValue("first", first), String.class);
        if (contracts.isEmpty()) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "E-KIDI-002", "진료일(" + first + ")을 보장하는 실손의료보험 계약이 없습니다.");
        }
        if (contracts.size() > 1) {
            throw new BusinessException(HttpStatus.CONFLICT, "E-KIDI-003", "진료일을 보장하는 실손 계약이 " + contracts.size()
                    + "건이라 자동으로 접수하지 않습니다. 보상 담당자가 확인합니다.");
        }
        ClaimRequests.Report report = ClaimRequests.Report.builder()
                .contractNo(contracts.get(0))
                .acdtDesc("실손24 전송 " + request.getTransmissionId() + " / " + request.getHospitalName() + "(" + request.getHospitalCode() + ")")
                .claimantId(request.getClaimantId())
                .medicalItems(items)
                .build();
        ClaimResponses.Detail detail = claimService.report(null, TX_PREFIX + request.getTransmissionId(), clientId, report,
                MedicalClaimService.SOURCE_KIDI);
        log.info("실손 청구 전산화 수신: 전송번호={}, 사고번호={}, 증권번호={}", request.getTransmissionId(), detail.getClmNo(),
                detail.getContract().getContractNo());
        return KidiClaimResponse.builder()
                .transmissionId(request.getTransmissionId())
                .clmNo(detail.getClmNo())
                .contractNo(detail.getContract().getContractNo())
                .clmStatCd(detail.getClmStatCd())
                .rptDt(detail.getRptDt())
                .build();
    }
}

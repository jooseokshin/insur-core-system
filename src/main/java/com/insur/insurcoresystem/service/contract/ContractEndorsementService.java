package com.insur.insurcoresystem.service.contract;

import static com.insur.insurcoresystem.common.validation.InputRules.isInvalidCode;
import static com.insur.insurcoresystem.service.contract.ContractSupport.isReadable;
import static com.insur.insurcoresystem.service.contract.ContractSupport.normalizeEmpId;
import static com.insur.insurcoresystem.service.contract.ContractSupport.pageRequest;

import com.insur.insurcoresystem.common.exception.BusinessException;
import com.insur.insurcoresystem.common.web.LogSanitizer;
import com.insur.insurcoresystem.domain.contract.Contract;
import com.insur.insurcoresystem.domain.contract.ContractEndorsement;
import com.insur.insurcoresystem.domain.contract.ContractEndorsementRepository;
import com.insur.insurcoresystem.domain.contract.ContractRepository;
import com.insur.insurcoresystem.domain.contract.ContractStatus;
import com.insur.insurcoresystem.service.contract.dto.ContractEndorseRequest;
import com.insur.insurcoresystem.service.contract.dto.ContractEndorseResponse;
import com.insur.insurcoresystem.service.contract.dto.ContractEndorseResponse.Change;
import com.insur.insurcoresystem.service.contract.dto.EndorsementHistoryPageResponse;
import com.insur.insurcoresystem.service.contract.dto.EndorsementHistoryRequest;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 계약 변경(배서) 서비스. 청약/유효 상태의 계약에서 계약자와 피보험자를 바꾸고, 바뀐 항목마다 변경 전/후 값을 이력으로 남긴다.
 * 보험료나 담보는 바꾸지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContractEndorsementService {

    static final String FIELD_POLICYHOLDER = "POLICYHOLDER_ID";
    static final String FIELD_INSURED = "INSURED_ID";

    private final ContractRepository contractRepository;
    private final ContractEndorsementRepository endorsementRepository;
    /** 업무 시간대(Asia/Seoul) 기준 현재 시각 */
    private final Clock clock;

    /**
     * 계약자/피보험자를 바꾼다. 계약 행을 잠근 채로 상태와 변경 내용을 확인하므로 같은 계약의 동시 변경은 한 번에 하나씩 처리된다.
     * 현재 값과 같은 값은 바뀐 것으로 보지 않으며, 바뀐 항목이 하나도 없으면 거절한다.
     * 같은 거래 UUID 로 이미 처리한 배서가 다시 들어오면(응답을 못 받은 재전송) 다시 바꾸지 않고 처음 결과를 돌려주고,
     * 내용이 다르면 거절한다(E-CTR-019, 409).
     *
     * @param globalTxId 거래 UUID (이력에 기록)
     * @param empId      처리 사번 (이력에 기록)
     * @param request    변경 요청 (증권번호, 새 계약자/피보험자)
     * @return 변경 후 계약자/피보험자와 실제로 바뀐 항목
     * @throws BusinessException 증권번호 누락(E-CTR-012), 거래 UUID 오류(E-CTR-008), 계약자/피보험자 ID 오류(E-CTR-005),
     *                           계약 없음(E-CTR-010, 404), 배서할 수 없는 상태(E-CTR-021, 409), 바뀐 항목 없음(E-CTR-022),
     *                           같은 거래 UUID 로 다른 내용의 변경(E-CTR-019, 409)
     */
    @Transactional
    public ContractEndorseResponse endorse(String globalTxId, String empId, ContractEndorseRequest request) {
        if (!StringUtils.hasText(request.getContractNo())) {
            throw new BusinessException("E-CTR-012", "증권번호는 필수입니다.");
        }
        if (isInvalidCode(globalTxId, 64)) {
            throw new BusinessException("E-CTR-008", "거래 UUID(globalTxId)는 필수이며 64자 이하여야 합니다.");
        }
        validateOptionalId(request.getPolicyholderId());
        validateOptionalId(request.getInsuredId());

        Contract contract = contractRepository.findForUpdateByContractNo(request.getContractNo())
                .orElseThrow(ContractSupport::contractNotFound);

        // 응답을 못 받은 클라이언트가 같은 거래 UUID 로 다시 보낸 경우: 이미 처리한 결과를 그대로 돌려준다 (멱등성)
        List<ContractEndorsement> done = endorsementRepository
                .findByContractNoAndGlobalTxIdOrderByEndorseIdAsc(contract.getContractNo(), globalTxId);
        if (!done.isEmpty()) {
            return replay(contract, request, done);
        }

        ContractStatus status = ContractStatus.fromCode(contract.getCtrStatCd());
        if (status != ContractStatus.APPLIED && status != ContractStatus.IN_FORCE) {
            throw new BusinessException(HttpStatus.CONFLICT, "E-CTR-021",
                    "청약 또는 유효 상태의 계약만 변경할 수 있습니다. (현재 상태: " + status.getCode() + ")");
        }

        List<Change> changes = new ArrayList<>();
        if (request.getPolicyholderId() != null && !request.getPolicyholderId().equals(contract.getPolicyholderId())) {
            changes.add(change(FIELD_POLICYHOLDER, contract.getPolicyholderId(), request.getPolicyholderId()));
            contract.changePolicyholder(request.getPolicyholderId());
        }
        if (request.getInsuredId() != null && !request.getInsuredId().equals(contract.getInsuredId())) {
            changes.add(change(FIELD_INSURED, contract.getInsuredId(), request.getInsuredId()));
            contract.changeInsured(request.getInsuredId());
        }
        if (changes.isEmpty()) {
            throw new BusinessException("E-CTR-022", "현재 값과 다른 변경 항목이 없습니다.");
        }

        LocalDateTime now = LocalDateTime.now(clock);
        endorsementRepository.saveAll(changes.stream().map(c -> ContractEndorsement.builder()
                .contractNo(contract.getContractNo())
                .fieldNm(c.getFieldNm())
                .beforeVal(c.getBeforeVal())
                .afterVal(c.getAfterVal())
                .empId(normalizeEmpId(empId))
                .globalTxId(globalTxId)
                .chgDt(now)
                .build()).toList());

        log.info("계약 변경(배서): 증권번호={}, 변경 항목={}, 거래={}", contract.getContractNo(),
                changes.stream().map(Change::getFieldNm).toList(), LogSanitizer.clean(globalTxId));
        return ContractEndorseResponse.builder()
                .contractNo(contract.getContractNo())
                .policyholderId(contract.getPolicyholderId())
                .insuredId(contract.getInsuredId())
                .changes(changes)
                .build();
    }

    /**
     * 계약의 배서 이력을 페이지 단위로 조회한다. 오래된 이력부터 돌려주며, 계약 조회와 같은 조회 범위 규칙을 따른다.
     *
     * @param ownerEmpId 조회를 접수자 본인의 계약으로 제한할 사번. null 이면 제한 없이 조회한다
     * @param request    조회 조건 (증권번호, 페이지 번호, 페이지 크기)
     * @return 해당 페이지의 배서 항목과 전체 건수/페이지 수
     * @throws BusinessException 증권번호 누락(E-CTR-009), 페이징 값 오류(E-CTR-016), 계약 없음 또는 조회 권한 밖(E-CTR-010)
     */
    @Transactional(readOnly = true)
    public EndorsementHistoryPageResponse history(String ownerEmpId, EndorsementHistoryRequest request) {
        if (!StringUtils.hasText(request.getContractNo())) {
            throw new BusinessException("E-CTR-009", "증권번호를 입력해야 합니다.");
        }
        Pageable pageable = pageRequest(request.getPage(), request.getSize(), Sort.by("endorseId").ascending());

        Contract contract = contractRepository.findById(request.getContractNo())
                .filter(found -> isReadable(found, ownerEmpId))
                .orElseThrow(ContractSupport::contractNotFound);

        Page<ContractEndorsement> result = endorsementRepository.findByContractNo(contract.getContractNo(), pageable);
        return EndorsementHistoryPageResponse.builder()
                .contractNo(contract.getContractNo())
                .page(pageable.getPageNumber())
                .size(pageable.getPageSize())
                .totalElements(result.getTotalElements())
                .totalPages(result.getTotalPages())
                .items(result.getContent().stream().map(e -> EndorsementHistoryPageResponse.Item.builder()
                        .fieldNm(e.getFieldNm())
                        .beforeVal(e.getBeforeVal())
                        .afterVal(e.getAfterVal())
                        .empId(e.getEmpId())
                        .chgDt(e.getChgDt())
                        .build()).toList())
                .build();
    }

    /**
     * 같은 거래 UUID 로 이미 처리한 배서의 재전송이면 처음 결과를 돌려준다. 처음에 바뀐 항목은 요청 값이 그때 바꾼 값과 같아야 하고,
     * 바뀌지 않았던 항목에 값을 넣었다면 그 값이 지금 계약 값과 같아야 한다. 그렇지 않으면 다른 내용의 요청이므로 거절한다.
     */
    private static ContractEndorseResponse replay(Contract contract, ContractEndorseRequest request,
                                                  List<ContractEndorsement> done) {
        boolean same = sameField(FIELD_POLICYHOLDER, request.getPolicyholderId(), contract.getPolicyholderId(), done)
                && sameField(FIELD_INSURED, request.getInsuredId(), contract.getInsuredId(), done);
        if (!same) {
            throw new BusinessException(HttpStatus.CONFLICT, "E-CTR-019",
                    "같은 거래 UUID(globalTxId)로 이미 다른 내용의 변경이 처리되었습니다. 새 거래 UUID 로 다시 요청해 주세요.");
        }
        log.info("이미 처리한 배서의 재전송이므로 기존 결과를 반환한다: 증권번호={}, 항목 {}건",
                contract.getContractNo(), done.size());
        return ContractEndorseResponse.builder()
                .contractNo(contract.getContractNo())
                .policyholderId(contract.getPolicyholderId())
                .insuredId(contract.getInsuredId())
                .changes(done.stream().map(e -> change(e.getFieldNm(), e.getBeforeVal(), e.getAfterVal())).toList())
                .build();
    }

    private static boolean sameField(String fieldNm, String requested, String currentValue, List<ContractEndorsement> done) {
        return done.stream().filter(e -> e.getFieldNm().equals(fieldNm)).findFirst()
                .map(e -> e.getAfterVal().equals(requested))           // 처음에 바꾼 항목: 그때 바꾼 값과 같아야 한다
                .orElse(requested == null || requested.equals(currentValue));   // 안 바뀐 항목: 비웠거나 현재 값과 같아야 한다
    }

    /** 입력한 ID 는 비어 있지 않고 컬럼 길이(20) 이하여야 한다. 입력하지 않은(null) 항목은 바꾸지 않는다는 뜻이다. */
    private static void validateOptionalId(String id) {
        if (id != null && isInvalidCode(id, 20)) {
            throw new BusinessException("E-CTR-005", "계약자/피보험자 ID는 비워 둘 수 없고 20자 이하여야 합니다.");
        }
    }

    private static Change change(String fieldNm, String before, String after) {
        return Change.builder().fieldNm(fieldNm).beforeVal(before).afterVal(after).build();
    }
}

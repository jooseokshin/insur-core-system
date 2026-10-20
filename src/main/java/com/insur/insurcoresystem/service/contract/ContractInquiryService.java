package com.insur.insurcoresystem.service.contract;

import static com.insur.insurcoresystem.service.contract.ContractSupport.isReadable;
import static com.insur.insurcoresystem.service.contract.ContractSupport.normalizeEmpId;
import static com.insur.insurcoresystem.service.contract.ContractSupport.pageRequest;

import com.insur.insurcoresystem.common.exception.BusinessException;
import com.insur.insurcoresystem.domain.contract.Contract;
import com.insur.insurcoresystem.domain.contract.ContractRepository;
import com.insur.insurcoresystem.domain.contract.ContractStatus;
import com.insur.insurcoresystem.domain.contract.ContractStatusHistory;
import com.insur.insurcoresystem.domain.contract.ContractStatusHistoryRepository;
import com.insur.insurcoresystem.domain.contract.CoverageRepository;
import com.insur.insurcoresystem.service.contract.dto.ContractInquiryRequest;
import com.insur.insurcoresystem.service.contract.dto.ContractInquiryResponse;
import com.insur.insurcoresystem.service.contract.dto.ContractListRequest;
import com.insur.insurcoresystem.service.contract.dto.ContractListResponse;
import com.insur.insurcoresystem.service.contract.dto.ContractSummaryResponse;
import com.insur.insurcoresystem.service.contract.dto.CoverageResponse;
import com.insur.insurcoresystem.service.contract.dto.StatusHistoryInquiryRequest;
import com.insur.insurcoresystem.service.contract.dto.StatusHistoryPageResponse;
import com.insur.insurcoresystem.service.contract.dto.StatusHistoryResponse;
import com.insur.insurcoresystem.service.sequence.ProductType;
import jakarta.persistence.criteria.Predicate;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 계약 조회/목록/상태 이력 조회 서비스. 모두 읽기 전용이며 접수자 제한 규칙을 따른다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContractInquiryService {

    private final ContractRepository contractRepository;
    private final CoverageRepository coverageRepository;
    private final ContractStatusHistoryRepository historyRepository;

    /** LIKE 조건의 이스케이프 문자 */
    private static final char LIKE_ESCAPE = '\\';

    /**
     * 증권번호 또는 청약번호로 계약 원장과 담보 목록을 조회한다. 두 값 중 정확히 하나만 입력해야 한다.
     * 접수자 제한이 있으면 그 사번이 접수한 계약만 조회할 수 있고, 그 외 계약은 존재 여부를 알 수 없도록 "계약 없음"과 똑같이 응답한다.
     *
     * @param ownerEmpId 조회를 접수자 본인의 계약으로 제한할 사번. null 이면 제한 없이 조회한다
     * @param request    조회 조건 (증권번호 또는 청약번호)
     * @return 계약 원장 정보와 담보 목록. 상태 변경 이력은 포함하지 않으며 {@link #inquireStatusHistory} 로 따로 조회한다
     * @throws BusinessException 조회 조건이 없거나 둘 다 입력된 경우(E-CTR-009), 해당 계약이 없거나 조회 권한 밖인 경우(E-CTR-010)
     */
    @Transactional(readOnly = true)
    public ContractInquiryResponse inquireContract(String ownerEmpId, ContractInquiryRequest request) {
        boolean byContractNo = StringUtils.hasText(request.getContractNo());
        boolean byApplNo = StringUtils.hasText(request.getApplNo());
        if (byContractNo == byApplNo) {
            throw new BusinessException("E-CTR-009", "증권번호 또는 청약번호 중 하나만 입력해야 합니다.");
        }

        Contract contract = (byContractNo
                ? contractRepository.findById(request.getContractNo())
                : contractRepository.findByApplNo(request.getApplNo()))
                .filter(found -> isReadable(found, ownerEmpId))
                .orElseThrow(ContractSupport::contractNotFound);

        List<CoverageResponse> coverages = coverageRepository.findByContractContractNo(contract.getContractNo())
                .stream()
                .map(cvrg -> CoverageResponse.builder()
                        .cvrgCd(cvrg.getCvrgCd())
                        .isrdAmt(cvrg.getIsrdAmt())
                        .prem(cvrg.getPrem())
                        .build())
                .toList();

        log.info("계약 조회: 증권번호={}, 담보 {}건", contract.getContractNo(), coverages.size());
        return ContractInquiryResponse.builder()
                .contractNo(contract.getContractNo())
                .applNo(contract.getApplNo())
                .insrClsCd(contract.getInsrClsCd())
                .ctrStatCd(contract.getCtrStatCd())
                .startDate(contract.getStartDate())
                .endDate(contract.getEndDate())
                .policyholderId(contract.getPolicyholderId())
                .insuredId(contract.getInsuredId())
                .totalPremium(contract.getTotalPremium())
                .sysRegDt(contract.getSysRegDt())
                .regEmpId(contract.getRegEmpId())
                .driverAge(contract.getDriverAge())
                .discountGrade(contract.getDiscountGrade())
                .structureGrade(contract.getStructureGrade())
                .renewedFrom(contract.getRenewedFrom())
                .termDate(contract.getTermDate())
                .refundPremium(contract.getRefundPremium())
                .coverages(coverages)
                .build();
    }

    /**
     * 계약의 상태 변경 이력을 페이지 단위로 조회한다. 오래된 이력부터 순서대로 돌려주며, 계약 조회와 같은 조회 범위 규칙을 따른다.
     * 페이지 번호는 0부터 시작하고, 값을 생략하면 첫 페이지를 한 페이지 {@value ContractSupport#DEFAULT_PAGE_SIZE}건으로 조회한다.
     *
     * @param ownerEmpId 조회를 접수자 본인의 계약으로 제한할 사번. null 이면 제한 없이 조회한다
     * @param request    조회 조건 (증권번호, 페이지 번호, 페이지 크기)
     * @return 해당 페이지의 이력과 전체 건수/페이지 수
     * @throws BusinessException 증권번호 누락(E-CTR-009), 페이지 번호/크기 오류(E-CTR-016),
     *                           계약 없음 또는 조회 권한 밖(E-CTR-010, 404)
     */
    @Transactional(readOnly = true)
    public StatusHistoryPageResponse inquireStatusHistory(String ownerEmpId, StatusHistoryInquiryRequest request) {
        if (!StringUtils.hasText(request.getContractNo())) {
            throw new BusinessException("E-CTR-009", "증권번호를 입력해야 합니다.");
        }
        Pageable pageable = pageRequest(request.getPage(), request.getSize(), Sort.by("histId").ascending());

        Contract contract = contractRepository.findById(request.getContractNo())
                .filter(found -> isReadable(found, ownerEmpId))
                .orElseThrow(ContractSupport::contractNotFound);

        Page<ContractStatusHistory> result = historyRepository.findByContractNo(contract.getContractNo(), pageable);

        log.info("상태 이력 조회: 증권번호={}, page={}, size={}, 전체 {}건",
                contract.getContractNo(), pageable.getPageNumber(), pageable.getPageSize(), result.getTotalElements());
        return StatusHistoryPageResponse.builder()
                .contractNo(contract.getContractNo())
                .page(pageable.getPageNumber())
                .size(pageable.getPageSize())
                .totalElements(result.getTotalElements())
                .totalPages(result.getTotalPages())
                .items(result.getContent().stream().map(ContractInquiryService::toHistoryResponse).toList())
                .build();
    }

    /**
     * 조건에 맞는 계약 목록을 페이지 단위로 조회한다. 조건은 모두 선택이며 지정한 조건은 AND 로 결합한다.
     * 기본 정렬은 최근 접수순이고, 접수일/보험시기/총보험료 기준으로 오름차순·내림차순을 고를 수 있다.
     * 청약 접수 담당(AGENT)만 가진 사용자는 접수자 제한이 걸려 본인이 접수한 계약만 목록에 나온다.
     * 페이지 번호는 0부터 시작하고, 값을 생략하면 첫 페이지를 한 페이지 {@value ContractSupport#DEFAULT_PAGE_SIZE}건으로 조회한다.
     *
     * @param ownerEmpId 목록을 접수자 본인의 계약으로 제한할 사번. null 이면 제한 없이 조회한다
     * @param request    검색 조건, 정렬, 페이지 번호, 페이지 크기
     * @return 해당 페이지의 계약 요약과 전체 건수/페이지 수
     * @throws BusinessException 지원하지 않는 보험종목(E-SEQ-001), 지원하지 않는 계약상태코드(E-CTR-013),
     *                           페이지 번호/크기 오류(E-CTR-016), 범위 조건 오류(E-CTR-017), 정렬 조건 오류(E-CTR-018)
     */
    @Transactional(readOnly = true)
    public ContractListResponse listContracts(String ownerEmpId, ContractListRequest request) {
        Pageable pageable = pageRequest(request.getPage(), request.getSize(),
                listSort(request.getSortBy(), request.getSortDir()));
        if (StringUtils.hasText(request.getInsrClsCd())) {
            ProductType.fromInsrClsCd(request.getInsrClsCd());   // 지원하지 않는 종목이면 E-SEQ-001
        }
        if (StringUtils.hasText(request.getCtrStatCd())) {
            ContractStatus.fromCode(request.getCtrStatCd());     // 지원하지 않는 상태코드면 E-CTR-013
        }
        LocalDate from = request.getRegDateFrom();
        LocalDate to = request.getRegDateTo();
        requireValidRange(from, to, "접수일");
        requireValidRange(request.getStartDateFrom(), request.getStartDateTo(), "보험시기");
        requireValidRange(request.getPremiumMin(), request.getPremiumMax(), "총보험료");
        if (request.getPremiumMin() != null && request.getPremiumMin().signum() < 0) {
            throw new BusinessException("E-CTR-017", "총보험료 최소값은 0 이상이어야 합니다.");
        }

        Specification<Contract> spec = (root, query, cb) -> {
            List<Predicate> conditions = new ArrayList<>();
            if (ownerEmpId != null) {   // 저장할 때와 같은 규칙(64자 제한)으로 맞춰 비교한다
                conditions.add(cb.equal(root.get("regEmpId"), normalizeEmpId(ownerEmpId)));
            }
            if (StringUtils.hasText(request.getContractNo())) {   // 앞부분 일치 (PK 인덱스를 탈 수 있다)
                conditions.add(cb.like(root.get("contractNo"), likePrefix(request.getContractNo()), LIKE_ESCAPE));
            }
            if (StringUtils.hasText(request.getApplNo())) {
                conditions.add(cb.like(root.get("applNo"), likePrefix(request.getApplNo()), LIKE_ESCAPE));
            }
            if (StringUtils.hasText(request.getInsrClsCd())) {
                conditions.add(cb.equal(root.get("insrClsCd"), request.getInsrClsCd()));
            }
            if (StringUtils.hasText(request.getCtrStatCd())) {
                conditions.add(cb.equal(root.get("ctrStatCd"), request.getCtrStatCd()));
            }
            if (StringUtils.hasText(request.getPolicyholderId())) {
                conditions.add(cb.equal(root.get("policyholderId"), request.getPolicyholderId()));
            }
            if (StringUtils.hasText(request.getInsuredId())) {
                conditions.add(cb.equal(root.get("insuredId"), request.getInsuredId()));
            }
            if (request.getStartDateFrom() != null) {
                conditions.add(cb.greaterThanOrEqualTo(root.<LocalDate>get("startDate"), request.getStartDateFrom()));
            }
            if (request.getStartDateTo() != null) {
                conditions.add(cb.lessThanOrEqualTo(root.<LocalDate>get("startDate"), request.getStartDateTo()));
            }
            if (request.getPremiumMin() != null) {
                conditions.add(cb.greaterThanOrEqualTo(root.<BigDecimal>get("totalPremium"), request.getPremiumMin()));
            }
            if (request.getPremiumMax() != null) {
                conditions.add(cb.lessThanOrEqualTo(root.<BigDecimal>get("totalPremium"), request.getPremiumMax()));
            }
            if (from != null) {   // 접수일 시작일 00:00 이상
                conditions.add(cb.greaterThanOrEqualTo(root.<LocalDateTime>get("sysRegDt"), from.atStartOfDay()));
            }
            if (to != null) {     // 접수일 종료일 당일 포함: 다음 날 00:00 미만
                conditions.add(cb.lessThan(root.<LocalDateTime>get("sysRegDt"), to.plusDays(1).atStartOfDay()));
            }
            return cb.and(conditions.toArray(new Predicate[0]));
        };

        Page<Contract> result = contractRepository.findAll(spec, pageable);

        log.info("계약 목록 조회: page={}, size={}, 전체 {}건", pageable.getPageNumber(), pageable.getPageSize(),
                result.getTotalElements());
        return ContractListResponse.builder()
                .page(pageable.getPageNumber())
                .size(pageable.getPageSize())
                .totalElements(result.getTotalElements())
                .totalPages(result.getTotalPages())
                .items(result.getContent().stream().map(ContractInquiryService::toSummaryResponse).toList())
                .build();
    }

    /**
     * 목록 정렬을 만든다. 정렬 기준은 접수일(REG_DATE, 기본), 보험시기(START_DATE), 총보험료(TOTAL_PREMIUM) 중에서 고르고,
     * 방향은 DESC(기본) 또는 ASC 이다. 같은 값끼리는 증권번호를 같은 방향으로 정렬해 페이지 경계가 흔들리지 않게 한다.
     *
     * @throws BusinessException 허용하지 않는 정렬 기준이나 방향인 경우(E-CTR-018)
     */
    private static Sort listSort(String sortBy, String sortDir) {
        String property = switch (StringUtils.hasText(sortBy) ? sortBy.toUpperCase() : "REG_DATE") {
            case "REG_DATE" -> "sysRegDt";
            case "START_DATE" -> "startDate";
            case "TOTAL_PREMIUM" -> "totalPremium";
            default -> throw new BusinessException("E-CTR-018",
                    "정렬 기준은 REG_DATE, START_DATE, TOTAL_PREMIUM 중 하나여야 합니다: " + sortBy);
        };
        Sort.Direction direction = switch (StringUtils.hasText(sortDir) ? sortDir.toUpperCase() : "DESC") {
            case "DESC" -> Sort.Direction.DESC;
            case "ASC" -> Sort.Direction.ASC;
            default -> throw new BusinessException("E-CTR-018", "정렬 방향은 ASC 또는 DESC 여야 합니다: " + sortDir);
        };
        return Sort.by(new Sort.Order(direction, property), new Sort.Order(direction, "contractNo"));
    }

    /** 범위 조건의 시작이 종료보다 크면 업무 오류(E-CTR-017)로 처리한다. 한쪽만 있거나 둘 다 없으면 통과한다. */
    private static <T extends Comparable<? super T>> void requireValidRange(T from, T to, String label) {
        if (from != null && to != null && from.compareTo(to) > 0) {
            throw new BusinessException("E-CTR-017", label + " 시작은 종료보다 클 수 없습니다.");
        }
    }

    /** LIKE 앞부분 일치 패턴을 만든다. 입력에 든 와일드카드(%, _)와 이스케이프 문자는 글자 그대로 비교하도록 이스케이프한다. */
    private static String likePrefix(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }

    /** 계약 원장을 목록용 요약 응답으로 바꾼다. (담보와 이력은 포함하지 않는다) */
    private static ContractSummaryResponse toSummaryResponse(Contract contract) {
        return ContractSummaryResponse.builder()
                .contractNo(contract.getContractNo())
                .applNo(contract.getApplNo())
                .insrClsCd(contract.getInsrClsCd())
                .ctrStatCd(contract.getCtrStatCd())
                .startDate(contract.getStartDate())
                .endDate(contract.getEndDate())
                .policyholderId(contract.getPolicyholderId())
                .totalPremium(contract.getTotalPremium())
                .sysRegDt(contract.getSysRegDt())
                .regEmpId(contract.getRegEmpId())
                .build();
    }

    /** 이력 엔티티를 응답 DTO 로 바꾼다. */
    private static StatusHistoryResponse toHistoryResponse(ContractStatusHistory hist) {
        return StatusHistoryResponse.builder()
                .beforeCtrStatCd(hist.getBeforeStatCd())
                .afterCtrStatCd(hist.getAfterStatCd())
                .empId(hist.getEmpId())
                .chgDt(hist.getChgDt())
                .build();
    }
}

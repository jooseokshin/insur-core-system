package com.insur.insurcoresystem.service.premium;

import static com.insur.insurcoresystem.common.validation.InputRules.isInvalidCode;
import static com.insur.insurcoresystem.service.contract.ContractSupport.normalizeEmpId;
import static com.insur.insurcoresystem.service.contract.ContractSupport.pageRequest;

import com.insur.insurcoresystem.common.exception.BusinessException;
import com.insur.insurcoresystem.common.web.LogSanitizer;
import com.insur.insurcoresystem.domain.premium.PremiumAudit;
import com.insur.insurcoresystem.domain.premium.PremiumAuditRepository;
import com.insur.insurcoresystem.domain.premium.PremiumFactor;
import com.insur.insurcoresystem.domain.premium.PremiumFactorPolicy;
import com.insur.insurcoresystem.domain.premium.PremiumFactorPolicyRepository;
import com.insur.insurcoresystem.domain.premium.PremiumFactorRepository;
import com.insur.insurcoresystem.domain.premium.PremiumRate;
import com.insur.insurcoresystem.domain.premium.PremiumRateRepository;
import com.insur.insurcoresystem.service.premium.dto.AuditListRequest;
import com.insur.insurcoresystem.service.premium.dto.AuditListResponse;
import com.insur.insurcoresystem.service.premium.dto.FactorListRequest;
import com.insur.insurcoresystem.service.premium.dto.FactorListResponse;
import com.insur.insurcoresystem.service.premium.dto.FactorListResponse.FactorItem;
import com.insur.insurcoresystem.service.premium.dto.FactorRevisionRequest;
import com.insur.insurcoresystem.service.premium.dto.FactorRevisionResponse;
import com.insur.insurcoresystem.service.premium.dto.PolicyListRequest;
import com.insur.insurcoresystem.service.premium.dto.PolicyListResponse;
import com.insur.insurcoresystem.service.premium.dto.PolicyListResponse.PolicyItem;
import com.insur.insurcoresystem.service.premium.dto.PolicyRevisionRequest;
import com.insur.insurcoresystem.service.premium.dto.PolicyRevisionResponse;
import com.insur.insurcoresystem.service.premium.dto.RateListRequest;
import com.insur.insurcoresystem.service.premium.dto.RateListResponse;
import com.insur.insurcoresystem.service.premium.dto.RateListResponse.RateItem;
import com.insur.insurcoresystem.service.premium.dto.RateRevisionRequest;
import com.insur.insurcoresystem.service.premium.dto.RateRevisionResponse;
import com.insur.insurcoresystem.service.sequence.ProductType;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 요율/요율 계수/필수 계수 정책 관리 서비스. 요율표를 조회하고, 개정하거나 새 칸을 만든다.
 * <ul>
 *     <li>개정은 기존 행을 고치거나 지우지 않는다. 가장 최근 행의 적용 종료일을 (새 시작일 − 1일)로 닫고 새 행을 추가한다.</li>
 *     <li>새 시작일은 오늘(서울 기준) 이후여야 한다. 과거 일자의 요율을 바꾸면 이미 나간 견적과 맞지 않게 되기 때문이다.</li>
 *     <li>모든 변경은 처리 사번과 거래 UUID 와 함께 감사 이력(TB_PRM_AUDIT)에 남고, 같은 거래 UUID 의 재전송은 처음 결과를 돌려준다.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PremiumAdminService {

    /** 계수 종류별 형태. 범위형은 구간(rangeFrom~rangeTo)으로, 키형은 코드(factorKey)로 칸이 정해진다. */
    private static final Map<String, Boolean> FACTOR_TYPE_IS_RANGE = Map.of(
            PremiumService.FACTOR_DRIVER_AGE, true,
            PremiumService.FACTOR_DISCOUNT_GRADE, false,
            PremiumService.FACTOR_STRUCTURE_GRADE, false);
    /** 보험종목별로 쓰는 계수 종류 */
    private static final Map<String, List<String>> FACTOR_TYPES_BY_PRODUCT = Map.of(
            "01", List.of(PremiumService.FACTOR_DRIVER_AGE, PremiumService.FACTOR_DISCOUNT_GRADE),
            "02", List.of(PremiumService.FACTOR_STRUCTURE_GRADE));
    private static final BigDecimal MAX_RATE = BigDecimal.ONE;
    private static final BigDecimal MAX_FACTOR = new BigDecimal("9.9999");
    private static final int MAX_RANGE_VALUE = 150;

    private final PremiumRateRepository rateRepository;
    private final PremiumFactorRepository factorRepository;
    private final PremiumFactorPolicyRepository policyRepository;
    private final PremiumAuditRepository auditRepository;
    /** 업무 시간대(Asia/Seoul) 기준 현재 시각 */
    private final Clock clock;

    // ----------------------------------------------------------------------------------------------- 요율

    /**
     * 요율 개정 이력을 조회한다.
     *
     * @param request 보험종목, 담보코드 조건 (모두 선택)
     * @return 조건에 맞는 요율 행 (보험종목, 담보코드, 적용 시작일 순)
     * @throws BusinessException 지원하지 않는 보험종목(E-SEQ-001)
     */
    @Transactional(readOnly = true)
    public RateListResponse listRates(RateListRequest request) {
        if (StringUtils.hasText(request.getInsrClsCd())) {
            ProductType.fromInsrClsCd(request.getInsrClsCd());
        }
        LocalDate today = LocalDate.now(clock);
        List<RateItem> items = rateRepository.findAll().stream()
                .filter(r -> !StringUtils.hasText(request.getInsrClsCd()) || r.getInsrClsCd().equals(request.getInsrClsCd()))
                .filter(r -> !StringUtils.hasText(request.getCvrgCd()) || r.getCvrgCd().equals(request.getCvrgCd()))
                .sorted(Comparator.comparing(PremiumRate::getInsrClsCd).thenComparing(PremiumRate::getCvrgCd)
                        .thenComparing(PremiumRate::getEffFrom))
                .map(r -> toItem(r, today))
                .toList();
        return RateListResponse.builder().items(items).build();
    }

    /**
     * 요율을 개정하거나 새 담보 요율을 만든다. 같은 거래 UUID 로 이미 처리했다면 다시 처리하지 않고 처음 결과를 돌려준다.
     *
     * @param globalTxId 거래 UUID (감사 이력과 재전송 판별에 쓴다)
     * @param empId      처리 사번
     * @param request    보험종목, 담보코드, 새 요율, 적용 시작일
     * @return 수행한 동작과 새 행, 닫은 이전 행
     * @throws BusinessException 입력 오류(E-ADM-001, E-CTR-006, E-SEQ-001, E-CTR-008), 과거 시작일(E-ADM-002),
     *                           최근 행보다 늦지 않은 시작일(E-ADM-003), 동시 작업/겹침 충돌(E-ADM-004),
     *                           같은 거래 UUID 로 다른 내용(E-CTR-019)
     */
    @Transactional
    public RateRevisionResponse reviseRate(String globalTxId, String empId, RateRevisionRequest request) {
        ProductType.fromInsrClsCd(request.getInsrClsCd());
        if (isInvalidCode(request.getCvrgCd(), 10)) {
            throw new BusinessException("E-CTR-006", "담보코드는 필수이며 10자 이하여야 합니다.");
        }
        validateRate(request.getAnnualRate());
        validateNameLength(request.getCvrgNm());
        requireTxId(globalTxId);
        LocalDate today = LocalDate.now(clock);
        requireEffFrom(request.getEffFrom(), today);

        String targetKey = request.getInsrClsCd() + "/" + request.getCvrgCd();
        Optional<PremiumAudit> done = auditRepository.findByTargetTypeAndGlobalTxId(PremiumAudit.TARGET_RATE, globalTxId);
        if (done.isPresent()) {
            return replayRate(done.get(), targetKey, request, today);
        }

        List<PremiumRate> history = rateRepository
                .findByInsrClsCdAndCvrgCdOrderByEffFromAsc(request.getInsrClsCd(), request.getCvrgCd());
        PremiumRate latest = history.isEmpty() ? null : history.get(history.size() - 1);
        if (latest == null && !StringUtils.hasText(request.getCvrgNm())) {
            throw new BusinessException("E-ADM-001", "새 담보의 요율을 만들 때는 담보명(cvrgNm)이 필요합니다.");
        }
        if (latest != null && !request.getEffFrom().isAfter(latest.getEffFrom())) {
            throw new BusinessException(HttpStatus.CONFLICT, "E-ADM-003", "새 적용 시작일(" + request.getEffFrom()
                    + ")은 가장 최근 요율의 시작일(" + latest.getEffFrom() + ")보다 늦어야 합니다.");
        }

        try {
            PremiumRate previous = null;
            if (latest != null && (latest.getEffTo() == null || !latest.getEffTo().isBefore(request.getEffFrom()))) {
                latest.closeAt(request.getEffFrom().minusDays(1));
                previous = rateRepository.saveAndFlush(latest);   // 새 행을 넣기 전에 먼저 닫아야 겹침 검사를 통과한다
            }
            PremiumRate created = rateRepository.saveAndFlush(PremiumRate.builder()
                    .insrClsCd(request.getInsrClsCd())
                    .cvrgCd(request.getCvrgCd())
                    .cvrgNm(StringUtils.hasText(request.getCvrgNm()) ? request.getCvrgNm() : latest.getCvrgNm())
                    .annualRate(request.getAnnualRate())
                    .effFrom(request.getEffFrom())
                    .build());
            audit(PremiumAudit.TARGET_RATE, targetKey, latest == null, latest == null ? null : plain(latest.getAnnualRate()),
                    plain(request.getAnnualRate()), request.getEffFrom(), empId, globalTxId);
            log.info("요율 {}: {} {} → {} (적용 시작 {}), 거래={}", latest == null ? "신규" : "개정", targetKey,
                    latest == null ? "-" : plain(latest.getAnnualRate()), plain(request.getAnnualRate()),
                    request.getEffFrom(), LogSanitizer.clean(globalTxId));
            return RateRevisionResponse.builder()
                    .action(latest == null ? PremiumAudit.ACTION_CREATE : PremiumAudit.ACTION_REVISE)
                    .current(toItem(created, today))
                    .previous(previous == null ? null : toItem(previous, today))
                    .build();
        } catch (DataIntegrityViolationException e) {
            throw conflict(e);
        }
    }

    private RateRevisionResponse replayRate(PremiumAudit done, String targetKey, RateRevisionRequest request, LocalDate today) {
        if (!done.getTargetKey().equals(targetKey) || !done.getEffFrom().equals(request.getEffFrom())
                || !done.getAfterVal().equals(plain(request.getAnnualRate()))) {
            throw replayMismatch();
        }
        List<PremiumRate> history = rateRepository
                .findByInsrClsCdAndCvrgCdOrderByEffFromAsc(request.getInsrClsCd(), request.getCvrgCd());
        PremiumRate current = history.stream().filter(r -> r.getEffFrom().equals(done.getEffFrom())).findFirst()
                .orElseThrow(MissingAuditTargetException::new);
        PremiumRate previous = history.stream().filter(r -> done.getEffFrom().minusDays(1).equals(r.getEffTo())).findFirst().orElse(null);
        log.info("이미 처리한 요율 변경의 재전송이므로 처음 결과를 반환한다: {}", targetKey);
        return RateRevisionResponse.builder().action(done.getAction())
                .current(toItem(current, today)).previous(previous == null ? null : toItem(previous, today)).build();
    }

    // ------------------------------------------------------------------------------------------- 요율 계수

    /**
     * 요율 계수 개정 이력을 조회한다.
     *
     * @param request 보험종목, 계수 종류 조건 (모두 선택)
     * @return 조건에 맞는 계수 행 (보험종목, 계수 종류, 키/구간, 적용 시작일 순)
     * @throws BusinessException 지원하지 않는 보험종목(E-SEQ-001), 알 수 없는 계수 종류(E-ADM-001)
     */
    @Transactional(readOnly = true)
    public FactorListResponse listFactors(FactorListRequest request) {
        if (StringUtils.hasText(request.getInsrClsCd())) {
            ProductType.fromInsrClsCd(request.getInsrClsCd());
        }
        if (StringUtils.hasText(request.getFactorType()) && !FACTOR_TYPE_IS_RANGE.containsKey(request.getFactorType())) {
            throw new BusinessException("E-ADM-001", "알 수 없는 계수 종류입니다: " + request.getFactorType());
        }
        LocalDate today = LocalDate.now(clock);
        List<FactorItem> items = factorRepository.findAll().stream()
                .filter(f -> !StringUtils.hasText(request.getInsrClsCd()) || f.getInsrClsCd().equals(request.getInsrClsCd()))
                .filter(f -> !StringUtils.hasText(request.getFactorType()) || f.getFactorType().equals(request.getFactorType()))
                .sorted(Comparator.comparing(PremiumFactor::getInsrClsCd).thenComparing(PremiumFactor::getFactorType)
                        .thenComparing(f -> f.getFactorKey() == null ? "" : f.getFactorKey())
                        .thenComparing(f -> f.getRangeFrom() == null ? -1 : f.getRangeFrom())
                        .thenComparing(PremiumFactor::getEffFrom))
                .map(f -> toItem(f, today))
                .toList();
        return FactorListResponse.builder().items(items).build();
    }

    /**
     * 요율 계수를 개정하거나 새 칸을 만든다. 같은 거래 UUID 로 이미 처리했다면 다시 처리하지 않고 처음 결과를 돌려준다.
     * 새 칸의 구간이 같은 기간의 다른 구간과 겹치면 DB 가 거절한다(E-ADM-004).
     *
     * @param globalTxId 거래 UUID (감사 이력과 재전송 판별에 쓴다)
     * @param empId      처리 사번
     * @param request    보험종목, 계수 종류, 키 또는 구간, 새 계수 값, 적용 시작일
     * @return 수행한 동작과 새 행, 닫은 이전 행
     * @throws BusinessException 입력 오류(E-ADM-001, E-SEQ-001, E-CTR-008), 과거 시작일(E-ADM-002),
     *                           최근 행보다 늦지 않은 시작일(E-ADM-003), 동시 작업/겹침 충돌(E-ADM-004),
     *                           같은 거래 UUID 로 다른 내용(E-CTR-019)
     */
    @Transactional
    public FactorRevisionResponse reviseFactor(String globalTxId, String empId, FactorRevisionRequest request) {
        ProductType.fromInsrClsCd(request.getInsrClsCd());
        validateFactorSlot(request);
        validateFactorValue(request.getFactorValue());
        validateNameLength(request.getFactorNm());
        requireTxId(globalTxId);
        LocalDate today = LocalDate.now(clock);
        requireEffFrom(request.getEffFrom(), today);

        String targetKey = request.getInsrClsCd() + "/" + request.getFactorType() + "/"
                + (request.getFactorKey() != null ? request.getFactorKey() : request.getRangeFrom() + "-" + request.getRangeTo());
        Optional<PremiumAudit> done = auditRepository.findByTargetTypeAndGlobalTxId(PremiumAudit.TARGET_FACTOR, globalTxId);
        if (done.isPresent()) {
            return replayFactor(done.get(), targetKey, request, today);
        }

        List<PremiumFactor> history = factorRepository
                .findByInsrClsCdAndFactorTypeOrderByEffFromAsc(request.getInsrClsCd(), request.getFactorType()).stream()
                .filter(f -> sameSlot(f, request))
                .toList();
        PremiumFactor latest = history.isEmpty() ? null : history.get(history.size() - 1);
        if (latest == null && !StringUtils.hasText(request.getFactorNm())) {
            throw new BusinessException("E-ADM-001", "새 계수 칸을 만들 때는 계수 이름(factorNm)이 필요합니다.");
        }
        if (latest != null && !request.getEffFrom().isAfter(latest.getEffFrom())) {
            throw new BusinessException(HttpStatus.CONFLICT, "E-ADM-003", "새 적용 시작일(" + request.getEffFrom()
                    + ")은 가장 최근 계수의 시작일(" + latest.getEffFrom() + ")보다 늦어야 합니다.");
        }

        try {
            PremiumFactor previous = null;
            if (latest != null && (latest.getEffTo() == null || !latest.getEffTo().isBefore(request.getEffFrom()))) {
                latest.closeAt(request.getEffFrom().minusDays(1));
                previous = factorRepository.saveAndFlush(latest);
            }
            PremiumFactor created = factorRepository.saveAndFlush(PremiumFactor.builder()
                    .insrClsCd(request.getInsrClsCd())
                    .factorType(request.getFactorType())
                    .factorKey(request.getFactorKey())
                    .rangeFrom(request.getRangeFrom())
                    .rangeTo(request.getRangeTo())
                    .factorNm(StringUtils.hasText(request.getFactorNm()) ? request.getFactorNm() : latest.getFactorNm())
                    .factorValue(request.getFactorValue())
                    .effFrom(request.getEffFrom())
                    .build());
            audit(PremiumAudit.TARGET_FACTOR, targetKey, latest == null,
                    latest == null ? null : plain(latest.getFactorValue()), plain(request.getFactorValue()),
                    request.getEffFrom(), empId, globalTxId);
            log.info("요율 계수 {}: {} {} → {} (적용 시작 {}), 거래={}", latest == null ? "신규" : "개정", targetKey,
                    latest == null ? "-" : plain(latest.getFactorValue()), plain(request.getFactorValue()),
                    request.getEffFrom(), LogSanitizer.clean(globalTxId));
            return FactorRevisionResponse.builder()
                    .action(latest == null ? PremiumAudit.ACTION_CREATE : PremiumAudit.ACTION_REVISE)
                    .current(toItem(created, today))
                    .previous(previous == null ? null : toItem(previous, today))
                    .build();
        } catch (DataIntegrityViolationException e) {
            throw conflict(e);
        }
    }

    private FactorRevisionResponse replayFactor(PremiumAudit done, String targetKey, FactorRevisionRequest request, LocalDate today) {
        if (!done.getTargetKey().equals(targetKey) || !done.getEffFrom().equals(request.getEffFrom())
                || !done.getAfterVal().equals(plain(request.getFactorValue()))) {
            throw replayMismatch();
        }
        List<PremiumFactor> history = factorRepository
                .findByInsrClsCdAndFactorTypeOrderByEffFromAsc(request.getInsrClsCd(), request.getFactorType()).stream()
                .filter(f -> sameSlot(f, request)).toList();
        PremiumFactor current = history.stream().filter(f -> f.getEffFrom().equals(done.getEffFrom())).findFirst()
                .orElseThrow(MissingAuditTargetException::new);
        PremiumFactor previous = history.stream().filter(f -> done.getEffFrom().minusDays(1).equals(f.getEffTo())).findFirst().orElse(null);
        log.info("이미 처리한 요율 계수 변경의 재전송이므로 처음 결과를 반환한다: {}", targetKey);
        return FactorRevisionResponse.builder().action(done.getAction())
                .current(toItem(current, today)).previous(previous == null ? null : toItem(previous, today)).build();
    }

    // --------------------------------------------------------------------------------------- 필수 계수 정책

    /**
     * 필수 요율 계수 정책의 개정 이력을 조회한다.
     *
     * @param request 보험종목 조건 (선택)
     * @return 조건에 맞는 정책 행 (보험종목, 계수 종류, 적용 시작일 순)
     * @throws BusinessException 지원하지 않는 보험종목(E-SEQ-001)
     */
    @Transactional(readOnly = true)
    public PolicyListResponse listPolicies(PolicyListRequest request) {
        if (StringUtils.hasText(request.getInsrClsCd())) {
            ProductType.fromInsrClsCd(request.getInsrClsCd());
        }
        LocalDate today = LocalDate.now(clock);
        List<PolicyItem> items = policyRepository.findAll().stream()
                .filter(p -> !StringUtils.hasText(request.getInsrClsCd()) || p.getInsrClsCd().equals(request.getInsrClsCd()))
                .sorted(Comparator.comparing(PremiumFactorPolicy::getInsrClsCd).thenComparing(PremiumFactorPolicy::getFactorType)
                        .thenComparing(PremiumFactorPolicy::getEffFrom))
                .map(p -> toItem(p, today))
                .toList();
        return PolicyListResponse.builder().items(items).build();
    }

    /**
     * 요율 관리 감사 이력을 조건에 맞게 최근 처리 순으로 페이지 단위로 조회한다.
     *
     * @param request 조회 조건 (대상 종류, 대상 식별 앞부분, 처리 사번, 처리일 범위, 페이징)
     * @return 해당 페이지의 감사 항목과 전체 건수/페이지 수
     * @throws BusinessException 알 수 없는 대상 종류나 거꾸로 된 처리일 범위(E-ADM-001), 페이징 값 오류(E-CTR-016)
     */
    @Transactional(readOnly = true)
    public AuditListResponse listAudits(AuditListRequest request) {
        if (StringUtils.hasText(request.getTargetType()) && !List.of(PremiumAudit.TARGET_RATE, PremiumAudit.TARGET_FACTOR,
                PremiumAudit.TARGET_POLICY).contains(request.getTargetType())) {
            throw new BusinessException("E-ADM-001", "알 수 없는 대상 종류입니다: " + LogSanitizer.clean(request.getTargetType()));
        }
        if (request.getFromDate() != null && request.getToDate() != null && request.getFromDate().isAfter(request.getToDate())) {
            throw new BusinessException("E-ADM-001", "처리일 시작이 종료보다 늦을 수 없습니다.");
        }
        var pageable = pageRequest(request.getPage(), request.getSize(), Sort.by("chgDt").descending().and(Sort.by("auditId").descending()));

        Specification<PremiumAudit> spec = (root, query, cb) -> {
            List<jakarta.persistence.criteria.Predicate> where = new java.util.ArrayList<>();
            if (StringUtils.hasText(request.getTargetType())) {
                where.add(cb.equal(root.get("targetType"), request.getTargetType()));
            }
            if (StringUtils.hasText(request.getTargetKey())) {
                String prefix = request.getTargetKey().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
                where.add(cb.like(root.get("targetKey"), prefix + "%", '\\'));
            }
            if (StringUtils.hasText(request.getEmpId())) {
                where.add(cb.equal(root.get("empId"), normalizeEmpId(request.getEmpId())));
            }
            if (request.getFromDate() != null) {
                where.add(cb.greaterThanOrEqualTo(root.get("chgDt"), request.getFromDate().atStartOfDay()));
            }
            if (request.getToDate() != null) {
                where.add(cb.lessThan(root.get("chgDt"), request.getToDate().plusDays(1).atStartOfDay()));
            }
            return cb.and(where.toArray(jakarta.persistence.criteria.Predicate[]::new));
        };
        Page<PremiumAudit> result = auditRepository.findAll(spec, pageable);
        return AuditListResponse.builder()
                .page(pageable.getPageNumber())
                .size(pageable.getPageSize())
                .totalElements(result.getTotalElements())
                .totalPages(result.getTotalPages())
                .items(result.getContent().stream().map(a -> AuditListResponse.AuditItem.builder()
                        .targetType(a.getTargetType()).targetKey(a.getTargetKey()).action(a.getAction())
                        .beforeVal(a.getBeforeVal()).afterVal(a.getAfterVal()).effFrom(a.getEffFrom())
                        .empId(a.getEmpId()).globalTxId(a.getGlobalTxId()).chgDt(a.getChgDt()).build()).toList())
                .build();
    }

    /**
     * 필수 요율 계수 정책을 개정하거나 새로 만든다. 새 정책은 보험시기가 적용 시작일 이후인 산출과 청약부터 적용된다.
     * 같은 거래 UUID 로 이미 처리했다면 다시 처리하지 않고 처음 결과를 돌려준다.
     *
     * @param globalTxId 거래 UUID (감사 이력과 재전송 판별에 쓴다)
     * @param empId      처리 사번
     * @param request    보험종목, 계수 종류, 필수 여부, 적용 시작일
     * @return 수행한 동작과 새 행, 닫은 이전 행
     * @throws BusinessException 입력 오류(E-ADM-001, E-SEQ-001, E-CTR-008), 과거 시작일(E-ADM-002),
     *                           최근 행보다 늦지 않은 시작일(E-ADM-003), 동시 작업/겹침 충돌(E-ADM-004),
     *                           같은 거래 UUID 로 다른 내용(E-CTR-019)
     */
    @Transactional
    public PolicyRevisionResponse revisePolicy(String globalTxId, String empId, PolicyRevisionRequest request) {
        ProductType.fromInsrClsCd(request.getInsrClsCd());
        if (!FACTOR_TYPE_IS_RANGE.containsKey(request.getFactorType())) {
            throw new BusinessException("E-ADM-001", "알 수 없는 계수 종류입니다: " + request.getFactorType());
        }
        if (!FACTOR_TYPES_BY_PRODUCT.get(request.getInsrClsCd()).contains(request.getFactorType())) {
            throw new BusinessException("E-ADM-001", "보험종목 " + request.getInsrClsCd()
                    + "에서 쓰지 않는 계수 종류입니다: " + request.getFactorType());
        }
        if (request.getRequired() == null) {
            throw new BusinessException("E-ADM-001", "필수 여부(required)는 필수입니다.");
        }
        requireTxId(globalTxId);
        LocalDate today = LocalDate.now(clock);
        requireEffFrom(request.getEffFrom(), today);

        String targetKey = request.getInsrClsCd() + "/" + request.getFactorType();
        String after = String.valueOf(request.getRequired());
        Optional<PremiumAudit> done = auditRepository.findByTargetTypeAndGlobalTxId(PremiumAudit.TARGET_POLICY, globalTxId);
        if (done.isPresent()) {
            return replayPolicy(done.get(), targetKey, after, request, today);
        }

        List<PremiumFactorPolicy> history = policyRepository
                .findByInsrClsCdAndFactorTypeOrderByEffFromAsc(request.getInsrClsCd(), request.getFactorType());
        PremiumFactorPolicy latest = history.isEmpty() ? null : history.get(history.size() - 1);
        if (latest != null && !request.getEffFrom().isAfter(latest.getEffFrom())) {
            throw new BusinessException(HttpStatus.CONFLICT, "E-ADM-003", "새 적용 시작일(" + request.getEffFrom()
                    + ")은 가장 최근 정책의 시작일(" + latest.getEffFrom() + ")보다 늦어야 합니다.");
        }

        try {
            PremiumFactorPolicy previous = null;
            if (latest != null && (latest.getEffTo() == null || !latest.getEffTo().isBefore(request.getEffFrom()))) {
                latest.closeAt(request.getEffFrom().minusDays(1));
                previous = policyRepository.saveAndFlush(latest);
            }
            PremiumFactorPolicy created = policyRepository.saveAndFlush(PremiumFactorPolicy.builder()
                    .insrClsCd(request.getInsrClsCd())
                    .factorType(request.getFactorType())
                    .required(request.getRequired())
                    .effFrom(request.getEffFrom())
                    .build());
            audit(PremiumAudit.TARGET_POLICY, targetKey, latest == null,
                    latest == null ? null : String.valueOf(latest.isRequired()), after, request.getEffFrom(), empId, globalTxId);
            log.info("필수 계수 정책 {}: {} {} → {} (적용 시작 {}), 거래={}", latest == null ? "신규" : "개정", targetKey,
                    latest == null ? "-" : latest.isRequired(), after, request.getEffFrom(), LogSanitizer.clean(globalTxId));
            return PolicyRevisionResponse.builder()
                    .action(latest == null ? PremiumAudit.ACTION_CREATE : PremiumAudit.ACTION_REVISE)
                    .current(toItem(created, today))
                    .previous(previous == null ? null : toItem(previous, today))
                    .build();
        } catch (DataIntegrityViolationException e) {
            throw conflict(e);
        }
    }

    private PolicyRevisionResponse replayPolicy(PremiumAudit done, String targetKey, String after,
                                                PolicyRevisionRequest request, LocalDate today) {
        if (!done.getTargetKey().equals(targetKey) || !done.getEffFrom().equals(request.getEffFrom())
                || !done.getAfterVal().equals(after)) {
            throw replayMismatch();
        }
        List<PremiumFactorPolicy> history = policyRepository
                .findByInsrClsCdAndFactorTypeOrderByEffFromAsc(request.getInsrClsCd(), request.getFactorType());
        PremiumFactorPolicy current = history.stream().filter(p -> p.getEffFrom().equals(done.getEffFrom())).findFirst()
                .orElseThrow(MissingAuditTargetException::new);
        PremiumFactorPolicy previous = history.stream()
                .filter(p -> done.getEffFrom().minusDays(1).equals(p.getEffTo())).findFirst().orElse(null);
        log.info("이미 처리한 필수 계수 정책 변경의 재전송이므로 처음 결과를 반환한다: {}", targetKey);
        return PolicyRevisionResponse.builder().action(done.getAction())
                .current(toItem(current, today)).previous(previous == null ? null : toItem(previous, today)).build();
    }

    // ------------------------------------------------------------------------------------------------ 공통

    /** 요율 행이 개정 이력에서 찾으려는 칸(키 또는 구간)에 속하는지 */
    private static boolean sameSlot(PremiumFactor factor, FactorRevisionRequest request) {
        if (request.getFactorKey() != null) {
            return request.getFactorKey().equals(factor.getFactorKey());
        }
        return factor.isRange() && factor.getRangeFrom().equals(request.getRangeFrom())
                && factor.getRangeTo().equals(request.getRangeTo());
    }

    private static void validateRate(BigDecimal rate) {
        if (rate == null || rate.signum() <= 0 || rate.compareTo(MAX_RATE) > 0 || rate.stripTrailingZeros().scale() > 6) {
            throw new BusinessException("E-ADM-001", "연 요율은 0보다 크고 1 이하이며 소수 6자리까지여야 합니다.");
        }
    }

    private static void validateFactorValue(BigDecimal value) {
        if (value == null || value.signum() <= 0 || value.compareTo(MAX_FACTOR) > 0 || value.stripTrailingZeros().scale() > 4) {
            throw new BusinessException("E-ADM-001", "계수 값은 0보다 크고 9.9999 이하이며 소수 4자리까지여야 합니다.");
        }
    }

    private static void validateNameLength(String name) {
        if (name != null && name.length() > 50) {
            throw new BusinessException("E-ADM-001", "이름은 50자 이하여야 합니다.");
        }
    }

    /** 계수 종류가 보험종목에서 쓰는 것이고, 종류에 맞는 형태(키 또는 구간)로만 입력했는지 확인한다. */
    private static void validateFactorSlot(FactorRevisionRequest request) {
        String type = request.getFactorType();
        if (!FACTOR_TYPE_IS_RANGE.containsKey(type)) {
            throw new BusinessException("E-ADM-001", "알 수 없는 계수 종류입니다: " + type);
        }
        if (!FACTOR_TYPES_BY_PRODUCT.get(request.getInsrClsCd()).contains(type)) {
            throw new BusinessException("E-ADM-001",
                    "보험종목 " + request.getInsrClsCd() + "에서 쓰지 않는 계수 종류입니다: " + type);
        }
        boolean hasKey = request.getFactorKey() != null;
        boolean hasRange = request.getRangeFrom() != null || request.getRangeTo() != null;
        if (FACTOR_TYPE_IS_RANGE.get(type)) {
            Integer from = request.getRangeFrom();
            Integer to = request.getRangeTo();
            if (hasKey || from == null || to == null || from < 0 || to > MAX_RANGE_VALUE || from > to) {
                throw new BusinessException("E-ADM-001",
                        type + " 은 구간(rangeFrom ≤ rangeTo, 0~" + MAX_RANGE_VALUE + ")만 입력해야 합니다.");
            }
        } else if (hasRange || isInvalidCode(request.getFactorKey(), 10)) {
            throw new BusinessException("E-ADM-001", type + " 은 코드(factorKey, 10자 이하)만 입력해야 합니다.");
        }
    }

    private static void requireTxId(String globalTxId) {
        if (isInvalidCode(globalTxId, 64)) {
            throw new BusinessException("E-CTR-008", "거래 UUID(globalTxId)는 필수이며 64자 이하여야 합니다.");
        }
    }

    private static void requireEffFrom(LocalDate effFrom, LocalDate today) {
        if (effFrom == null) {
            throw new BusinessException("E-ADM-001", "적용 시작일(effFrom)은 필수입니다.");
        }
        if (effFrom.isBefore(today)) {
            throw new BusinessException("E-ADM-002", "적용 시작일은 오늘(" + today + ") 이후여야 합니다. 과거 일자로는 개정할 수 없습니다.");
        }
    }

    private void audit(String targetType, String targetKey, boolean created, String before, String after,
                       LocalDate effFrom, String empId, String globalTxId) {
        auditRepository.saveAndFlush(PremiumAudit.builder()
                .targetType(targetType)
                .targetKey(targetKey)
                .action(created ? PremiumAudit.ACTION_CREATE : PremiumAudit.ACTION_REVISE)
                .beforeVal(before)
                .afterVal(after)
                .effFrom(effFrom)
                .empId(normalizeEmpId(empId))
                .globalTxId(globalTxId)
                .chgDt(LocalDateTime.now(clock))
                .build());
    }

    private static BusinessException replayMismatch() {
        return new BusinessException(HttpStatus.CONFLICT, "E-CTR-019",
                "같은 거래 UUID(globalTxId)로 이미 다른 내용의 변경이 처리되었습니다. 새 거래 UUID 로 다시 요청해 주세요.");
    }

    /** 적용 기간 겹침 트리거(23P01)나 거래 UUID 유니크 위반(동시 작업)을 충돌 오류로 바꾼다. */
    private static BusinessException conflict(DataIntegrityViolationException e) {
        log.warn("요율 관리 작업이 충돌했다: {}", LogSanitizer.clean(e.getMostSpecificCause().getMessage()));
        return new BusinessException(HttpStatus.CONFLICT, "E-ADM-004",
                "다른 요율 변경과 충돌했습니다. 적용 기간이 겹치거나 같은 거래가 동시에 처리되었습니다. 목록을 다시 조회한 뒤 요청해 주세요.");
    }

    private static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private static String status(LocalDate effFrom, LocalDate effTo, LocalDate today) {
        if (effFrom.isAfter(today)) {
            return "FUTURE";
        }
        return effTo != null && effTo.isBefore(today) ? "CLOSED" : "ACTIVE";
    }

    private static RateItem toItem(PremiumRate r, LocalDate today) {
        return RateItem.builder().rateId(r.getRateId()).insrClsCd(r.getInsrClsCd()).cvrgCd(r.getCvrgCd())
                .cvrgNm(r.getCvrgNm()).annualRate(r.getAnnualRate()).effFrom(r.getEffFrom()).effTo(r.getEffTo())
                .status(status(r.getEffFrom(), r.getEffTo(), today)).build();
    }

    private static PolicyItem toItem(PremiumFactorPolicy p, LocalDate today) {
        return PolicyItem.builder().policyId(p.getPolicyId()).insrClsCd(p.getInsrClsCd()).factorType(p.getFactorType())
                .required(p.isRequired()).effFrom(p.getEffFrom()).effTo(p.getEffTo())
                .status(status(p.getEffFrom(), p.getEffTo(), today)).build();
    }

    private static FactorItem toItem(PremiumFactor f, LocalDate today) {
        return FactorItem.builder().factorId(f.getFactorId()).insrClsCd(f.getInsrClsCd()).factorType(f.getFactorType())
                .factorKey(f.getFactorKey()).rangeFrom(f.getRangeFrom()).rangeTo(f.getRangeTo()).factorNm(f.getFactorNm())
                .factorValue(f.getFactorValue()).effFrom(f.getEffFrom()).effTo(f.getEffTo())
                .status(status(f.getEffFrom(), f.getEffTo(), today)).build();
    }

    /** 감사 이력은 있는데 대응하는 행을 찾지 못하는 비정상 상태(행이 수동으로 삭제된 경우 등). 시스템 오류로 처리한다. */
    private static final class MissingAuditTargetException extends IllegalStateException {
        MissingAuditTargetException() {
            super("감사 이력에 대응하는 요율/계수 행이 없습니다.");
        }
    }
}

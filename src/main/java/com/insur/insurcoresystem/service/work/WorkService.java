package com.insur.insurcoresystem.service.work;

import static com.insur.insurcoresystem.service.contract.ContractSupport.normalizeEmpId;
import static com.insur.insurcoresystem.service.contract.ContractSupport.pageRequest;

import com.insur.insurcoresystem.common.exception.BusinessException;
import com.insur.insurcoresystem.common.security.Roles;
import com.insur.insurcoresystem.domain.contract.ContractStatus;
import com.insur.insurcoresystem.service.claim.ClaimService;
import com.insur.insurcoresystem.service.contract.ContractRenewalService;
import com.insur.insurcoresystem.service.sequence.ProductType;
import com.insur.insurcoresystem.service.work.dto.DashboardResponse;
import com.insur.insurcoresystem.service.work.dto.RenewalTargetRequest;
import com.insur.insurcoresystem.service.work.dto.RenewalTargetResponse;
import com.insur.insurcoresystem.service.work.dto.TodoRequest;
import com.insur.insurcoresystem.service.work.dto.TodoResponse;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 현업 업무 화면용 조회 서비스: 업무 현황(대시보드), 할 일함, 만기·갱신 대상.
 * 여러 테이블을 묶어 세는 읽기 전용 집계라서 엔티티 대신 SQL 로 직접 조회한다. 모두 읽기 전용이며 계약 조회와 같은 접수자 제한 규칙을 따른다.
 * "오늘"은 업무 시간대(Asia/Seoul) 날짜이고, 일시 컬럼도 업무 시간대로 저장되어 있으므로 날짜 경계를 그대로 비교한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkService {

    /** 업무 현황에서 보상 현황을 보는 역할 */
    private static final Set<String> CLAIM_VIEWERS = Set.of(Roles.AGENT, Roles.CLAIM, Roles.ADMIN);

    /** 접수 후 이 일수 이상 청약 상태로 남으면 장기 미처리로 본다 */
    public static final int STALE_DAYS = 7;

    /** 계약별 납입 합계. 납입이 없는 계약은 0 으로 본다 */
    private static final String PAID_JOIN = """
            LEFT JOIN (SELECT CONTRACT_NO, SUM(PAY_AMT) AS PAID FROM TB_CTR_PAYMENT GROUP BY CONTRACT_NO) P
                   ON P.CONTRACT_NO = C.CONTRACT_NO
            """;

    /** 아직 청약취소되지 않은 갱신 계약이 없는 경우 (갱신 유니크 인덱스 V17 과 같은 기준) */
    private static final String NOT_RENEWED = """
            NOT EXISTS (SELECT 1 FROM TB_CTR_LGD R WHERE R.RENEWED_FROM = C.CONTRACT_NO AND R.CTR_STAT_CD <> '90')
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;

    /**
     * 업무 현황을 집계한다. 계약상태별 건수, 오늘 처리 실적, 사용자가 처리할 수 있는 할 일 건수, 갱신 대상 건수를 한 번에 돌려준다.
     *
     * @param ownerEmpId 집계를 접수자 본인의 계약으로 제한할 사번. null 이면 제한 없이 집계한다
     * @param roles      사용자 역할 (ROLE_ 접두사 없이). 할 일 건수는 이 역할로 처리할 수 있는 유형만 센다
     */
    @Transactional(readOnly = true)
    public DashboardResponse dashboard(String ownerEmpId, Set<String> roles) {
        LocalDate today = LocalDate.now(clock);
        MapSqlParameterSource params = baseParams(ownerEmpId, today);
        String owner = ownerCondition(ownerEmpId);

        List<DashboardResponse.StatusCount> statusCounts = jdbc.query("""
                SELECT C.CTR_STAT_CD, COUNT(*) AS CNT, COALESCE(SUM(C.TOTAL_PREMIUM), 0) AS PREM
                  FROM TB_CTR_LGD C
                 WHERE 1 = 1""" + owner + """
                 GROUP BY C.CTR_STAT_CD
                 ORDER BY C.CTR_STAT_CD
                """, params, (rs, i) -> DashboardResponse.StatusCount.builder()
                .ctrStatCd(rs.getString("CTR_STAT_CD"))
                .count(rs.getLong("CNT"))
                .totalPremium(rs.getBigDecimal("PREM"))
                .build());

        DashboardResponse.Today todayStats = jdbc.queryForObject("""
                SELECT (SELECT COUNT(*) FROM TB_CTR_LGD C
                         WHERE C.SYS_REG_DT >= :dayStart AND C.SYS_REG_DT < :dayEnd""" + owner + """
                       ) AS APPLIED_CNT,
                       (SELECT COALESCE(SUM(C.TOTAL_PREMIUM), 0) FROM TB_CTR_LGD C
                         WHERE C.SYS_REG_DT >= :dayStart AND C.SYS_REG_DT < :dayEnd""" + owner + """
                       ) AS APPLIED_PREM,
                       (SELECT COUNT(*) FROM TB_CTR_STAT_HIST H JOIN TB_CTR_LGD C ON C.CONTRACT_NO = H.CONTRACT_NO
                         WHERE H.AFTER_STAT_CD = '20' AND H.CHG_DT >= :dayStart AND H.CHG_DT < :dayEnd""" + owner + """
                       ) AS APPROVED_CNT,
                       (SELECT COUNT(*) FROM TB_CTR_STAT_HIST H JOIN TB_CTR_LGD C ON C.CONTRACT_NO = H.CONTRACT_NO
                         WHERE H.AFTER_STAT_CD = '90' AND H.CHG_DT >= :dayStart AND H.CHG_DT < :dayEnd""" + owner + """
                       ) AS CANCELED_CNT,
                       (SELECT COUNT(*) FROM TB_CTR_STAT_HIST H JOIN TB_CTR_LGD C ON C.CONTRACT_NO = H.CONTRACT_NO
                         WHERE H.AFTER_STAT_CD = '30' AND H.CHG_DT >= :dayStart AND H.CHG_DT < :dayEnd""" + owner + """
                       ) AS TERMINATED_CNT,
                       (SELECT COUNT(*) FROM TB_CTR_PAYMENT X JOIN TB_CTR_LGD C ON C.CONTRACT_NO = X.CONTRACT_NO
                         WHERE X.PAID_DT >= :dayStart AND X.PAID_DT < :dayEnd""" + owner + """
                       ) AS PAID_CNT,
                       (SELECT COALESCE(SUM(X.PAY_AMT), 0) FROM TB_CTR_PAYMENT X JOIN TB_CTR_LGD C ON C.CONTRACT_NO = X.CONTRACT_NO
                         WHERE X.PAID_DT >= :dayStart AND X.PAID_DT < :dayEnd""" + owner + """
                       ) AS PAID_AMT
                """, params, (rs, i) -> DashboardResponse.Today.builder()
                .appliedCount(rs.getLong("APPLIED_CNT"))
                .appliedPremium(rs.getBigDecimal("APPLIED_PREM"))
                .approvedCount(rs.getLong("APPROVED_CNT"))
                .canceledCount(rs.getLong("CANCELED_CNT"))
                .terminatedCount(rs.getLong("TERMINATED_CNT"))
                .paidCount(rs.getLong("PAID_CNT"))
                .paidAmount(rs.getBigDecimal("PAID_AMT"))
                .build());

        List<DashboardResponse.TodoCount> todoCounts = Arrays.stream(TodoType.values())
                .filter(type -> type.isVisibleTo(roles))
                .map(type -> DashboardResponse.TodoCount.builder()
                        .type(type.name())
                        .label(type.getLabel())
                        .count(count("SELECT COUNT(*) FROM TB_CTR_LGD C " + PAID_JOIN
                                + " WHERE " + todoCondition(type) + owner, params))
                        .build())
                .toList();

        long renewalDue = count("SELECT COUNT(*) FROM TB_CTR_LGD C WHERE " + renewalCondition() + owner,
                params.addValue("dueTo", today.plusDays(ContractRenewalService.RENEWAL_WINDOW_DAYS)));

        DashboardResponse.Claims claims = roles.stream().anyMatch(CLAIM_VIEWERS::contains)
                ? claimCounts(params.addValue("threshold", BigDecimal.valueOf(ClaimService.HIGH_VALUE_THRESHOLD)), owner)
                : null;

        log.info("업무 현황 조회: 기준일={}, 본인 계약만={}", today, ownerEmpId != null);
        return DashboardResponse.builder()
                .baseDate(today)
                .ownContractsOnly(ownerEmpId != null)
                .statusCounts(statusCounts)
                .today(todayStats)
                .todoCounts(todoCounts)
                .renewalDueCount(renewalDue)
                .claims(claims)
                .build();
    }

    /**
     * 할 일함 목록을 조회한다. 청약 상태의 계약을 오래된 접수순으로 돌려준다.
     *
     * @param ownerEmpId 목록을 접수자 본인의 계약으로 제한할 사번. null 이면 제한 없이 조회한다
     * @param roles      사용자 역할 (ROLE_ 접두사 없이)
     * @throws BusinessException 유형 오류(E-WRK-001), 그 유형을 처리할 역할이 없는 경우(E-AUTH-002, 403), 페이징 값 오류(E-CTR-016)
     */
    @Transactional(readOnly = true)
    public TodoResponse todo(String ownerEmpId, Set<String> roles, TodoRequest request) {
        TodoType type = TodoType.from(request.getType());
        if (!type.isVisibleTo(roles)) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "E-AUTH-002", type.getLabel() + " 업무를 처리할 권한이 없습니다.");
        }
        PageRequest pageable = pageRequest(request.getPage(), request.getSize(), Sort.unsorted());
        LocalDate today = LocalDate.now(clock);
        MapSqlParameterSource params = baseParams(ownerEmpId, today)
                .addValue("limit", pageable.getPageSize())
                .addValue("offset", pageable.getOffset());
        String where = " WHERE " + todoCondition(type) + ownerCondition(ownerEmpId);

        long total = count("SELECT COUNT(*) FROM TB_CTR_LGD C " + PAID_JOIN + where, params);
        List<TodoResponse.Item> items = jdbc.query("""
                SELECT C.CONTRACT_NO, C.APPL_NO, C.INSR_CLS_CD, C.POLICYHOLDER_ID, C.START_DATE, C.TOTAL_PREMIUM,
                       COALESCE(P.PAID, 0) AS PAID, C.SYS_REG_DT, C.REG_EMP_ID
                  FROM TB_CTR_LGD C
                """ + PAID_JOIN + where + """
                 ORDER BY C.SYS_REG_DT, C.CONTRACT_NO
                 LIMIT :limit OFFSET :offset
                """, params, (rs, i) -> {
            BigDecimal totalPremium = rs.getBigDecimal("TOTAL_PREMIUM");
            BigDecimal paid = rs.getBigDecimal("PAID");
            LocalDateTime regDt = rs.getObject("SYS_REG_DT", LocalDateTime.class);
            return TodoResponse.Item.builder()
                    .contractNo(rs.getString("CONTRACT_NO"))
                    .applNo(rs.getString("APPL_NO"))
                    .insrClsCd(rs.getString("INSR_CLS_CD"))
                    .policyholderId(rs.getString("POLICYHOLDER_ID"))
                    .startDate(rs.getObject("START_DATE", LocalDate.class))
                    .totalPremium(totalPremium)
                    .paidPremium(paid)
                    .remainingPremium(totalPremium.subtract(paid).max(BigDecimal.ZERO))
                    .sysRegDt(regDt)
                    .regEmpId(rs.getString("REG_EMP_ID"))
                    .elapsedDays(ChronoUnit.DAYS.between(regDt.toLocalDate(), today))
                    .build();
        });

        log.info("할 일함 조회: 유형={}, page={}, size={}, 전체 {}건", type, pageable.getPageNumber(), pageable.getPageSize(), total);
        return TodoResponse.builder()
                .type(type.name())
                .page(pageable.getPageNumber())
                .size(pageable.getPageSize())
                .totalElements(total)
                .totalPages(totalPages(total, pageable.getPageSize()))
                .items(items)
                .build();
    }

    /**
     * 만기·갱신 대상을 조회한다. 오늘부터 지정한 일수 안에 보험종기가 오는 유효 계약 중 아직 갱신하지 않은 것을, 종기가 가까운 순으로 돌려준다.
     * 조회 기간은 갱신 가능 기간({@value ContractRenewalService#RENEWAL_WINDOW_DAYS}일)을 넘을 수 없으므로 여기 나온 계약은 바로 갱신할 수 있다.
     *
     * @param ownerEmpId 목록을 접수자 본인의 계약으로 제한할 사번. null 이면 제한 없이 조회한다
     * @throws BusinessException 조회 기간 오류(E-WRK-002), 지원하지 않는 보험종목(E-SEQ-001), 페이징 값 오류(E-CTR-016)
     */
    @Transactional(readOnly = true)
    public RenewalTargetResponse renewalTargets(String ownerEmpId, RenewalTargetRequest request) {
        int withinDays = request.getWithinDays() == null ? ContractRenewalService.RENEWAL_WINDOW_DAYS : request.getWithinDays();
        if (withinDays < 1 || withinDays > ContractRenewalService.RENEWAL_WINDOW_DAYS) {
            throw new BusinessException("E-WRK-002",
                    "조회 기간은 1~" + ContractRenewalService.RENEWAL_WINDOW_DAYS + "일 사이여야 합니다.");
        }
        boolean byClass = StringUtils.hasText(request.getInsrClsCd());
        if (byClass) {
            ProductType.fromInsrClsCd(request.getInsrClsCd());   // 지원하지 않는 종목이면 E-SEQ-001
        }
        PageRequest pageable = pageRequest(request.getPage(), request.getSize(), Sort.unsorted());
        LocalDate today = LocalDate.now(clock);
        MapSqlParameterSource params = baseParams(ownerEmpId, today)
                .addValue("dueTo", today.plusDays(withinDays))
                .addValue("insrClsCd", request.getInsrClsCd())
                .addValue("limit", pageable.getPageSize())
                .addValue("offset", pageable.getOffset());
        String where = " WHERE " + renewalCondition() + ownerCondition(ownerEmpId)
                + (byClass ? " AND C.INSR_CLS_CD = :insrClsCd" : "");

        long total = count("SELECT COUNT(*) FROM TB_CTR_LGD C" + where, params);
        List<RenewalTargetResponse.Item> items = jdbc.query("""
                SELECT C.CONTRACT_NO, C.INSR_CLS_CD, C.POLICYHOLDER_ID, C.INSURED_ID, C.START_DATE, C.END_DATE,
                       C.TOTAL_PREMIUM, C.REG_EMP_ID
                  FROM TB_CTR_LGD C""" + where + """
                 ORDER BY C.END_DATE, C.CONTRACT_NO
                 LIMIT :limit OFFSET :offset
                """, params, (rs, i) -> {
            LocalDate endDate = rs.getObject("END_DATE", LocalDate.class);
            return RenewalTargetResponse.Item.builder()
                    .contractNo(rs.getString("CONTRACT_NO"))
                    .insrClsCd(rs.getString("INSR_CLS_CD"))
                    .policyholderId(rs.getString("POLICYHOLDER_ID"))
                    .insuredId(rs.getString("INSURED_ID"))
                    .startDate(rs.getObject("START_DATE", LocalDate.class))
                    .endDate(endDate)
                    .daysToEnd(ChronoUnit.DAYS.between(today, endDate))
                    .totalPremium(rs.getBigDecimal("TOTAL_PREMIUM"))
                    .regEmpId(rs.getString("REG_EMP_ID"))
                    .build();
        });

        log.info("만기·갱신 대상 조회: {}일 이내, page={}, size={}, 전체 {}건",
                withinDays, pageable.getPageNumber(), pageable.getPageSize(), total);
        return RenewalTargetResponse.builder()
                .baseDate(today)
                .withinDays(withinDays)
                .page(pageable.getPageNumber())
                .size(pageable.getPageSize())
                .totalElements(total)
                .totalPages(totalPages(total, pageable.getPageSize()))
                .items(items)
                .build();
    }

    /** 보상 현황을 세는 SQL. 설계사는 본인이 접수한 계약의 사고만 센다 */
    private DashboardResponse.Claims claimCounts(MapSqlParameterSource params, String owner) {
        String from = " FROM TB_CLM X JOIN TB_CTR_LGD C ON C.CONTRACT_NO = X.CONTRACT_NO WHERE 1 = 1" + owner;
        return jdbc.queryForObject("SELECT "
                + "(SELECT COUNT(*)" + from + " AND X.CLM_STAT_CD = '10') AS REPORTED, "
                + "(SELECT COUNT(*)" + from + " AND X.CLM_STAT_CD = '20') AS ASSESSED, "
                + "(SELECT COUNT(*)" + from + " AND X.CLM_STAT_CD = '20' AND X.TOTAL_PAY_AMT > :threshold) AS HIGH_VALUE, "
                + "(SELECT COUNT(*)" + from + " AND X.RPT_DT >= :dayStart AND X.RPT_DT < :dayEnd) AS TODAY_REPORTED, "
                + "(SELECT COUNT(*)" + from + " AND X.CLM_STAT_CD = '40' AND X.DECIDE_DT >= :dayStart AND X.DECIDE_DT < :dayEnd) AS TODAY_PAID, "
                + "(SELECT COALESCE(SUM(X.TOTAL_PAY_AMT), 0)" + from
                + " AND X.CLM_STAT_CD = '40' AND X.DECIDE_DT >= :dayStart AND X.DECIDE_DT < :dayEnd) AS TODAY_PAID_AMT",
                params, (rs, i) -> DashboardResponse.Claims.builder()
                        .reportedCount(rs.getLong("REPORTED"))
                        .assessedCount(rs.getLong("ASSESSED"))
                        .highValuePendingCount(rs.getLong("HIGH_VALUE"))
                        .todayReportedCount(rs.getLong("TODAY_REPORTED"))
                        .todayPaidCount(rs.getLong("TODAY_PAID"))
                        .todayPaidAmount(rs.getBigDecimal("TODAY_PAID_AMT"))
                        .build());
    }

    /** 오늘의 시작·끝 시각, 장기 미처리 기준 시각, 접수자 제한 사번을 담은 공통 파라미터 */
    private static MapSqlParameterSource baseParams(String ownerEmpId, LocalDate today) {
        return new MapSqlParameterSource()
                .addValue("today", today)
                .addValue("dayStart", today.atStartOfDay())
                .addValue("dayEnd", today.plusDays(1).atStartOfDay())
                // 접수일이 오늘로부터 STALE_DAYS 일 이상 지난 건: 접수일 < 오늘 - (STALE_DAYS - 1)
                .addValue("staleBefore", today.minusDays(STALE_DAYS - 1L).atStartOfDay())
                .addValue("owner", ownerEmpId == null ? null : normalizeEmpId(ownerEmpId));
    }

    /** 접수자 제한이 있으면 접수 사번 조건을 붙인다 (저장할 때와 같은 64자 규칙으로 맞춘 값과 비교) */
    private static String ownerCondition(String ownerEmpId) {
        return ownerEmpId == null ? "" : " AND C.REG_EMP_ID = :owner";
    }

    /** 할 일 유형별 조건. 모두 청약(10) 상태의 계약이고, 납입 합계(P.PAID)를 쓰는 조건은 PAID_JOIN 과 함께 쓴다 */
    private static String todoCondition(TodoType type) {
        String applied = "C.CTR_STAT_CD = '" + ContractStatus.APPLIED.getCode() + "'";
        return switch (type) {
            case PENDING_APPROVAL -> applied + " AND COALESCE(P.PAID, 0) >= C.TOTAL_PREMIUM";
            case UNPAID -> applied + " AND COALESCE(P.PAID, 0) < C.TOTAL_PREMIUM";
            case STALE_APPLICATION -> applied + " AND C.SYS_REG_DT < :staleBefore";
        };
    }

    /** 갱신 대상 조건: 유효(20) 계약 중 보험종기가 오늘~:dueTo 이고 아직 갱신하지 않은 것 */
    private static String renewalCondition() {
        return "C.CTR_STAT_CD = '" + ContractStatus.IN_FORCE.getCode() + "'"
                + " AND C.END_DATE BETWEEN :today AND :dueTo AND " + NOT_RENEWED;
    }

    private long count(String sql, MapSqlParameterSource params) {
        Long count = jdbc.queryForObject(sql, params, Long.class);
        return count == null ? 0 : count;
    }

    private static int totalPages(long total, int size) {
        return (int) ((total + size - 1) / size);
    }
}

package com.insur.insurcoresystem.service.work;

import static com.insur.insurcoresystem.service.contract.ContractSupport.normalizeEmpId;
import static com.insur.insurcoresystem.service.contract.ContractSupport.pageRequest;

import com.insur.insurcoresystem.common.exception.BusinessException;
import com.insur.insurcoresystem.common.paging.PageQuery;
import com.insur.insurcoresystem.common.security.Roles;
import com.insur.insurcoresystem.mapper.work.WorkMapper;
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
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
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
    private final WorkMapper workMapper;
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
        WorkMapper.Condition base = baseCondition(ownerEmpId, today);

        List<DashboardResponse.StatusCount> statusCounts = workMapper.countByStatus(base);
        DashboardResponse.Today todayStats = workMapper.todayStats(base);

        List<DashboardResponse.TodoCount> todoCounts = Arrays.stream(TodoType.values())
                .filter(type -> type.isVisibleTo(roles))
                .map(type -> DashboardResponse.TodoCount.builder()
                        .type(type.name())
                        .label(type.getLabel())
                        .count(workMapper.countTodo(base.toBuilder().todoType(type.name()).build()))
                        .build())
                .toList();

        long renewalDue = workMapper.countRenewalTargets(
                base.toBuilder().dueTo(today.plusDays(ContractRenewalService.RENEWAL_WINDOW_DAYS)).build());

        DashboardResponse.Claims claims = roles.stream().anyMatch(CLAIM_VIEWERS::contains)
                ? workMapper.claimCounts(base.toBuilder().threshold(BigDecimal.valueOf(ClaimService.HIGH_VALUE_THRESHOLD)).build())
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
        PageQuery pageable = pageRequest(request.getPage(), request.getSize());
        LocalDate today = LocalDate.now(clock);
        WorkMapper.Condition condition = baseCondition(ownerEmpId, today).toBuilder().todoType(type.name()).build();

        long total = workMapper.countTodo(condition);
        List<TodoResponse.Item> items = workMapper.findTodo(condition, pageable).stream()
                .map(r -> TodoResponse.Item.builder()
                        .contractNo(r.contractNo())
                        .applNo(r.applNo())
                        .insrClsCd(r.insrClsCd())
                        .policyholderId(r.policyholderId())
                        .startDate(r.startDate())
                        .totalPremium(r.totalPremium())
                        .paidPremium(r.paid())
                        .remainingPremium(r.totalPremium().subtract(r.paid()).max(BigDecimal.ZERO))
                        .sysRegDt(r.sysRegDt())
                        .regEmpId(r.regEmpId())
                        .elapsedDays(ChronoUnit.DAYS.between(r.sysRegDt().toLocalDate(), today))
                        .build())
                .toList();

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
        PageQuery pageable = pageRequest(request.getPage(), request.getSize());
        LocalDate today = LocalDate.now(clock);
        WorkMapper.Condition condition = baseCondition(ownerEmpId, today).toBuilder()
                .dueTo(today.plusDays(withinDays))
                .insrClsCd(byClass ? request.getInsrClsCd() : null)
                .build();

        long total = workMapper.countRenewalTargets(condition);
        List<RenewalTargetResponse.Item> items = workMapper.findRenewalTargets(condition, pageable).stream()
                .map(r -> RenewalTargetResponse.Item.builder()
                        .contractNo(r.contractNo())
                        .insrClsCd(r.insrClsCd())
                        .policyholderId(r.policyholderId())
                        .insuredId(r.insuredId())
                        .startDate(r.startDate())
                        .endDate(r.endDate())
                        .daysToEnd(ChronoUnit.DAYS.between(today, r.endDate()))
                        .totalPremium(r.totalPremium())
                        .regEmpId(r.regEmpId())
                        .build())
                .toList();

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
    private static WorkMapper.Condition baseCondition(String ownerEmpId, LocalDate today) {
        return WorkMapper.Condition.builder()
                .today(today)
                .dayStart(today.atStartOfDay())
                .dayEnd(today.plusDays(1).atStartOfDay())
                // 접수일이 오늘로부터 STALE_DAYS 일 이상 지난 건: 접수일 < 오늘 - (STALE_DAYS - 1)
                .staleBefore(today.minusDays(STALE_DAYS - 1L).atStartOfDay())
                .owner(ownerEmpId == null ? null : normalizeEmpId(ownerEmpId))
                .build();
    }

    private static int totalPages(long total, int size) {
        return (int) ((total + size - 1) / size);
    }
}

package com.insur.insurcoresystem.mapper.stats;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/**
 * 통계 매퍼 (읽기 전용): 손해율, 요율 영향, IBNR 진전 삼각형.
 * 경과보험료 정의(SQL 조각 {@code exposure})는 세 분석이 함께 쓴다: 유효(20)·해지(30) 계약의 담보 보험료 × 기간 안의 보장 일수 ÷ 보험기간 일수,
 * 보장 종료는 min(보험종기, 해지일) 미포함.
 */
public interface StatsMapper {

    /**
     * 경과보험료와 계약 수. 묶음 키가 모두 false 면 전체 한 줄이고, 계약은 중복 없이 센다.
     *
     * @param fromDate 기간 시작 (포함)
     * @param toExcl   기간 끝 다음 날 (미포함)
     */
    List<EarnedRow> aggregateEarned(@Param("g") GroupKeys keys, @Param("fromDate") LocalDate fromDate,
                                    @Param("toExcl") LocalDate toExcl);

    /** 사고일이 기간 안인 손해 (지급 = 40 지급액, 미결 = 20 지급 예정액 + 10 청구액, 80·90 제외) */
    List<LossRow> aggregateLosses(@Param("g") GroupKeys keys, @Param("fromDate") LocalDate fromDate,
                                  @Param("toExcl") LocalDate toExcl);

    /** 한 보험종목의 경과보험료 합계 */
    BigDecimal earnedPremium(@Param("insrClsCd") String insrClsCd, @Param("fromDate") LocalDate fromDate,
                             @Param("toExcl") LocalDate toExcl);

    /** 오늘 적용 중인 담보별 연 요율 (담보코드 순) */
    List<TariffRow> currentTariff(@Param("insrClsCd") String insrClsCd, @Param("today") LocalDate today);

    /** 경험 기간 중(시작일 다음 날 ~ 종료일)에 시작한 요율·계수 개정과 오늘 이후로 예정된 개정 */
    List<RevisionRow> revisions(@Param("insrClsCd") String insrClsCd, @Param("fromDate") LocalDate fromDate,
                                @Param("toDate") LocalDate toDate, @Param("today") LocalDate today);

    /**
     * 오늘 유효한 계약과, 그 가운데 renewTo 까지 만기가 오고 아직 갱신하지 않은 계약 (갱신 대상 화면과 같은 기준).
     *
     * @param byCoverage true 면 담보별, false 면 종목 전체 한 줄 (계약은 중복 없이)
     */
    List<InForceRow> inForce(@Param("insrClsCd") String insrClsCd, @Param("today") LocalDate today,
                             @Param("renewTo") LocalDate renewTo, @Param("byCoverage") boolean byCoverage);

    /** 기준일까지 지급(40)된 사고: 사고일, 지급일, 지급액 */
    List<PaidClaimRow> paidClaims(@Param("insrClsCd") String insrClsCd, @Param("from") LocalDate from,
                                  @Param("eval") LocalDate eval, @Param("evalExcl") LocalDateTime evalExcl);

    /** 기준일의 개별추산액: 기준일 전에 접수되고 그때 종결되지 않은 사고 (사정했으면 지급 예정액, 아니면 청구액) */
    List<CaseReserveRow> caseReserves(@Param("insrClsCd") String insrClsCd, @Param("from") LocalDate from,
                                      @Param("eval") LocalDate eval, @Param("evalExcl") LocalDateTime evalExcl);

    /** 손해율 묶음 키 */
    record GroupKeys(boolean byClass, boolean byProduct, boolean byCoverage) {
    }

    record EarnedRow(String insrClsCd, String prdCd, String cvrgCd, long cnt, BigDecimal earned) {
    }

    record LossRow(String insrClsCd, String prdCd, String cvrgCd, long cnt, BigDecimal paid, BigDecimal outstanding) {
    }

    record TariffRow(String cvrgCd, String cvrgNm, BigDecimal annualRate) {
    }

    /** kind: RATE(담보 요율, target = 담보코드), FACTOR(요율 계수, target = 계수 종류) */
    record RevisionRow(String kind, String target, LocalDate effFrom) {
    }

    /** cnt·prem: 유효 계약, rcnt·rprem: 그 가운데 갱신 대상 */
    record InForceRow(String cvrgCd, long cnt, BigDecimal prem, long rcnt, BigDecimal rprem) {
    }

    record PaidClaimRow(LocalDate acdtDt, LocalDate paidDt, BigDecimal totalPayAmt) {
    }

    record CaseReserveRow(LocalDate acdtDt, BigDecimal rsv) {
    }
}

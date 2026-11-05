package com.insur.insurcoresystem.domain.claim;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 보상 상태 이력. 추가 전용이며, (사고번호, 거래 UUID) 가 유일해서 같은 처리의 재전송을 판별하는 데도 쓴다.
 */
@Entity
@Table(name = "TB_CLM_HIST")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class ClaimHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "HIST_ID")
    private Long histId;

    @Column(name = "CLM_NO", length = 30, nullable = false)
    private String clmNo;

    /** 변경 전 상태 (접수는 null) */
    @Column(name = "BEFORE_STAT_CD", length = 2)
    private String beforeStatCd;

    @Column(name = "AFTER_STAT_CD", length = 2, nullable = false)
    private String afterStatCd;

    @Column(name = "EMP_ID", length = 64)
    private String empId;

    @Column(name = "MEMO", length = 500)
    private String memo;

    @Column(name = "GLOBAL_TX_ID", length = 64, nullable = false)
    private String globalTxId;

    @Column(name = "CHG_DT", nullable = false)
    private LocalDateTime chgDt;
}

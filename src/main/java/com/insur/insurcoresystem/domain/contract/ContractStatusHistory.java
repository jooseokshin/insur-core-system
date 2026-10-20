package com.insur.insurcoresystem.domain.contract;

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
 * 계약상태 변경 이력. 추가 전용이며 한 번 저장한 이력은 수정하거나 삭제하지 않는다.
 */
@Entity
@Table(name = "TB_CTR_STAT_HIST")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class ContractStatusHistory {

    /** 이력 일련번호 (발생 순서) */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "HIST_ID")
    private Long histId;

    /** 증권번호 */
    @Column(name = "CONTRACT_NO", length = 30, nullable = false)
    private String contractNo;

    /** 변경 전 계약상태코드. 청약 접수(최초 상태)는 null */
    @Column(name = "BEFORE_STAT_CD", length = 2)
    private String beforeStatCd;

    /** 변경 후 계약상태코드 */
    @Column(name = "AFTER_STAT_CD", length = 2, nullable = false)
    private String afterStatCd;

    /** 처리 사번 (인증된 JWT 의 subject) */
    @Column(name = "EMP_ID", length = 64)
    private String empId;

    /** 변경을 일으킨 거래 UUID */
    @Column(name = "GLOBAL_TX_ID", length = 64, nullable = false)
    private String globalTxId;

    /** 변경 일시 */
    @Column(name = "CHG_DT", nullable = false)
    private LocalDateTime chgDt;
}

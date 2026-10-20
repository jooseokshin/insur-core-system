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
 * 계약 변경(배서) 이력. 바뀐 항목 하나가 한 건이며, 추가 전용이라 저장한 이력은 수정하거나 삭제하지 않는다.
 */
@Entity
@Table(name = "TB_CTR_ENDORSE")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class ContractEndorsement {

    /** 배서 항목 일련번호 (발생 순서) */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "ENDORSE_ID")
    private Long endorseId;

    /** 증권번호 */
    @Column(name = "CONTRACT_NO", length = 30, nullable = false)
    private String contractNo;

    /** 변경한 항목 (POLICYHOLDER_ID: 계약자, INSURED_ID: 피보험자) */
    @Column(name = "FIELD_NM", length = 30, nullable = false)
    private String fieldNm;

    /** 변경 전 값 */
    @Column(name = "BEFORE_VAL", length = 100)
    private String beforeVal;

    /** 변경 후 값 */
    @Column(name = "AFTER_VAL", length = 100)
    private String afterVal;

    /** 처리 사번 */
    @Column(name = "EMP_ID", length = 64)
    private String empId;

    /** 변경을 일으킨 거래 UUID */
    @Column(name = "GLOBAL_TX_ID", length = 64, nullable = false)
    private String globalTxId;

    /** 변경 일시 */
    @Column(name = "CHG_DT", nullable = false)
    private LocalDateTime chgDt;
}

package com.insur.insurcoresystem.domain.contract;

import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 계약 변경(배서) 이력. 바뀐 항목 하나가 한 건이며, 추가 전용이라 저장한 이력은 수정하거나 삭제하지 않는다.
 */
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class ContractEndorsement {

    /** 배서 항목 일련번호 (발생 순서) */
    private Long endorseId;

    /** 증권번호 */
    private String contractNo;

    /** 변경한 항목 (POLICYHOLDER_ID: 계약자, INSURED_ID: 피보험자) */
    private String fieldNm;

    /** 변경 전 값 */
    private String beforeVal;

    /** 변경 후 값 */
    private String afterVal;

    /** 처리 사번 */
    private String empId;

    /** 변경을 일으킨 거래 UUID */
    private String globalTxId;

    /** 변경 일시 */
    private LocalDateTime chgDt;
}

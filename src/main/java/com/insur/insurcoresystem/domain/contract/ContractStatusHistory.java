package com.insur.insurcoresystem.domain.contract;

import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 계약상태 변경 이력. 추가 전용이며 한 번 저장한 이력은 수정하거나 삭제하지 않는다.
 */
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class ContractStatusHistory {

    /** 이력 일련번호 (발생 순서) */
    private Long histId;

    /** 증권번호 */
    private String contractNo;

    /** 변경 전 계약상태코드. 청약 접수(최초 상태)는 null */
    private String beforeStatCd;

    /** 변경 후 계약상태코드 */
    private String afterStatCd;

    /** 처리 사번 (인증된 JWT 의 subject) */
    private String empId;

    /** 변경을 일으킨 거래 UUID */
    private String globalTxId;

    /** 변경 일시 */
    private LocalDateTime chgDt;
}

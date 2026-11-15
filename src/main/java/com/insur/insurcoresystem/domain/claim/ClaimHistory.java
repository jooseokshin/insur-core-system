package com.insur.insurcoresystem.domain.claim;

import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 보상 상태 이력. 추가 전용이며, (사고번호, 거래 UUID) 가 유일해서 같은 처리의 재전송을 판별하는 데도 쓴다.
 */
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class ClaimHistory {

    private Long histId;

    private String clmNo;

    /** 변경 전 상태 (접수는 null) */
    private String beforeStatCd;

    private String afterStatCd;

    private String empId;

    private String memo;

    private String globalTxId;

    private LocalDateTime chgDt;
}

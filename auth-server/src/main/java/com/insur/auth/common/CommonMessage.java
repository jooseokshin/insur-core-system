package com.insur.auth.common;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 공통 전문 (헤더 + 바디)
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class CommonMessage<T> {

    /** 공통 전문 헤더 */
    private CommonHeader header;
    /** 업무별 전문 바디. 오류 응답에서는 null */
    private T body;

    /**
     * 요청 바디를 꺼낸다.
     *
     * @throws BusinessException 바디가 없는 경우(E-CMN-001)
     */
    public T requireBody() {
        if (body == null) {
            throw new BusinessException("E-CMN-001", "요청 전문 바디가 존재하지 않습니다.");
        }
        return body;
    }

    /** 요청 헤더의 globalTxId, empId 를 이어받아 정상 응답 전문을 만든다. */
    public static <T> CommonMessage<T> success(CommonHeader reqHeader, T body) {
        CommonHeader header = CommonHeader.builder()
                .globalTxId(reqHeader.getGlobalTxId())
                .empId(reqHeader.getEmpId())
                .responseCode(CommonHeader.SUCCESS_CODE)
                .responseMsg(CommonHeader.SUCCESS_MSG)
                .build();
        return new CommonMessage<>(header, body);
    }
}

package com.insur.insurcoresystem.common.web;

import com.insur.insurcoresystem.common.dto.CommonHeader;
import com.insur.insurcoresystem.common.dto.CommonMessage;
import com.insur.insurcoresystem.common.exception.BusinessException;

/**
 * 컨트롤러가 공통 전문(헤더 + 바디)을 다룰 때 공통으로 쓰는 헬퍼.
 */
public final class CommonMessages {

    private CommonMessages() {
    }

    /**
     * 요청 전문의 바디를 꺼낸다.
     *
     * @throws BusinessException 바디가 없는 경우(E-CMN-001)
     */
    public static <T> T requireBody(CommonMessage<T> request) {
        if (request.getBody() == null) {
            throw new BusinessException("E-CMN-001", "요청 전문 바디가 존재하지 않습니다.");
        }
        return request.getBody();
    }

    /** 요청 헤더의 globalTxId, empId 를 이어받아 정상 응답 전문을 만든다. */
    public static <T> CommonMessage<T> success(CommonHeader reqHeader, T body) {
        CommonHeader resHeader = CommonHeader.builder()
                .globalTxId(reqHeader.getGlobalTxId())
                .empId(reqHeader.getEmpId())
                .responseCode(CommonHeader.SUCCESS_CODE)
                .responseMsg(CommonHeader.SUCCESS_MSG)
                .build();
        return new CommonMessage<>(resHeader, body);
    }
}

package com.insur.auth.common.paging;

import java.util.List;

/**
 * 페이지 조회 결과: 이 페이지의 행과 조건에 맞는 전체 건수.
 *
 * @param content       이 페이지의 행
 * @param page          요청한 페이지
 * @param totalElements 전체 건수 (별도 COUNT 쿼리)
 */
public record PageResult<T>(List<T> content, PageQuery page, long totalElements) {

    public List<T> getContent() {
        return content;
    }

    public long getTotalElements() {
        return totalElements;
    }

    public int getTotalPages() {
        return page.getPageSize() == 0 ? 1 : (int) Math.ceil((double) totalElements / page.getPageSize());
    }
}

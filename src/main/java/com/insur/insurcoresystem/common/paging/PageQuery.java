package com.insur.insurcoresystem.common.paging;

/**
 * 페이지 조회 요청 (0부터 세는 페이지 번호와 크기). 매퍼 XML 에서 {@code LIMIT #{page.pageSize} OFFSET #{page.offset}} 로 쓴다.
 * MyBatis 는 record 의 구성 요소만 속성으로 보므로 계산 속성(offset)을 읽을 수 있게 일반 클래스로 둔다.
 */
public final class PageQuery {

    private final int pageNumber;
    private final int pageSize;

    private PageQuery(int pageNumber, int pageSize) {
        this.pageNumber = pageNumber;
        this.pageSize = pageSize;
    }

    /**
     * @param pageNumber 페이지 번호 (0부터)
     * @param pageSize   페이지 크기
     */
    public static PageQuery of(int pageNumber, int pageSize) {
        return new PageQuery(pageNumber, pageSize);
    }

    public int getPageNumber() {
        return pageNumber;
    }

    public int getPageSize() {
        return pageSize;
    }

    /** 건너뛸 행 수 */
    public long getOffset() {
        return (long) pageNumber * pageSize;
    }
}

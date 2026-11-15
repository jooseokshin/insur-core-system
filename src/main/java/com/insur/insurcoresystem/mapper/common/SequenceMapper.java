package com.insur.insurcoresystem.mapper.common;

import org.apache.ibatis.annotations.Param;

/**
 * 채번 테이블(TB_CMN_SEQ) 매퍼.
 */
public interface SequenceMapper {

    /**
     * 번호유형·상품구분·연도 단위의 일련번호를 한 문장(UPSERT ... RETURNING)으로 증가시키고 새 번호를 돌려준다.
     * 행 잠금은 호출 트랜잭션이 끝날 때까지 유지된다.
     */
    long next(@Param("seqType") String seqType, @Param("prodCd") String prodCd, @Param("seqYear") String seqYear);
}

package com.insur.insurcoresystem.mapper.product;

import com.insur.insurcoresystem.domain.product.Product;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 상품(TB_PRD)과 상품 담보 구성(TB_PRD_CVRG) 매퍼. 상품을 읽으면 담보 구성도 정렬 순서대로 함께 채운다. 상품은 마이그레이션으로만 관리한다.
 */
public interface ProductMapper {

    Optional<Product> findById(String prdCd);

    /** 보험종목의 상품 (상품코드 순) */
    List<Product> findByInsrClsCdOrderByPrdCd(String insrClsCd);

    /** 전체 상품 (보험종목, 상품코드 순) */
    List<Product> findAllByOrderByInsrClsCdAscPrdCdAsc();

    /** 상품코드 여러 개로 조회 */
    List<Product> findAllById(Collection<String> prdCds);
}

package com.insur.insurcoresystem.domain.product;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 상품 Repository
 */
public interface ProductRepository extends JpaRepository<Product, String> {

    /** 보험종목의 상품 (상품코드순) */
    List<Product> findByInsrClsCdOrderByPrdCd(String insrClsCd);

    /** 전체 상품 (보험종목, 상품코드순) */
    List<Product> findAllByOrderByInsrClsCdAscPrdCdAsc();
}

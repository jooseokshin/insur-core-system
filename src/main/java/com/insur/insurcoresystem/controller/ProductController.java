package com.insur.insurcoresystem.controller;

import static com.insur.insurcoresystem.common.web.CommonMessages.requireBody;
import static com.insur.insurcoresystem.common.web.CommonMessages.success;

import com.insur.insurcoresystem.common.dto.CommonMessage;
import com.insur.insurcoresystem.common.exception.BusinessException;
import com.insur.insurcoresystem.service.product.ProductService;
import com.insur.insurcoresystem.service.product.dto.ProductListRequest;
import com.insur.insurcoresystem.service.product.dto.ProductListResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 상품 조회 컨트롤러. 상품은 마이그레이션으로 관리하므로 조회만 제공한다.
 */
@Tag(name = "상품", description = "상품과 담보 구성(주계약·특약, 가입금액 한도, 자기부담금) 조회")
@RestController
@RequestMapping("/api/product")
@RequiredArgsConstructor
public class ProductController {

    private final ProductService productService;

    /**
     * 상품 목록과 담보 구성을 조회한다. (POST /api/product/list)
     *
     * @param request 조회 조건 (보험종목, 판매 중만, 상품코드)
     * @return 상품 목록
     * @throws BusinessException 요청 바디가 없는 경우(E-CMN-001), 지원하지 않는 보험종목(E-SEQ-001)
     */
    @Operation(summary = "상품 목록과 담보 구성을 조회한다", description = "보험종목, 오늘 판매 중인 상품만, 상품코드로 좁힐 수 있다. 담보마다 주계약·특약 구분, 필수 여부, 가입금액 한도, 자기부담금을 담는다.\n\n오류: 요청 바디가 없는 경우(E-CMN-001), 지원하지 않는 보험종목(E-SEQ-001)")
    @PostMapping("/list")
    public CommonMessage<ProductListResponse> list(@RequestBody CommonMessage<ProductListRequest> request) {
        return success(request.getHeader(), productService.list(requireBody(request)));
    }
}

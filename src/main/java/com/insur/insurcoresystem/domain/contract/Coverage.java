package com.insur.insurcoresystem.domain.contract;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 계약 담보
 */
@Entity
@Table(name = "TB_CTR_CVRG")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class Coverage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "ID")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "CONTRACT_NO", nullable = false)
    private Contract contract;

    /** 담보코드 */
    @Column(name = "CVRG_CD", length = 10, nullable = false)
    private String cvrgCd;

    /** 가입금액 */
    @Column(name = "ISRD_AMT", precision = 15, scale = 0, nullable = false)
    private BigDecimal isrdAmt;

    /** 담보보험료 */
    @Column(name = "PREM", precision = 15, scale = 0, nullable = false)
    private BigDecimal prem;
}

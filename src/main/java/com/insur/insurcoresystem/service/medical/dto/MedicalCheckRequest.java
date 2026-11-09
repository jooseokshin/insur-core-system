package com.insur.insurcoresystem.service.medical.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 실손 중복가입 다시 조회 요청
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MedicalCheckRequest {
    /** 증권번호 (청약 상태의 실손 계약) */
    @Schema(description = "증권번호 (청약 상태의 실손 계약)")
    private String contractNo;
}

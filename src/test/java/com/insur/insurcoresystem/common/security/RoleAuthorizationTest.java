package com.insur.insurcoresystem.common.security;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.insur.insurcoresystem.service.contract.ContractEndorsementService;
import com.insur.insurcoresystem.service.contract.ContractInquiryService;
import com.insur.insurcoresystem.service.contract.ContractService;
import com.insur.insurcoresystem.service.contract.ContractStatusService;
import com.insur.insurcoresystem.service.premium.PremiumService;
import com.insur.insurcoresystem.service.premium.dto.PremiumQuoteResponse;
import com.insur.insurcoresystem.service.contract.dto.ContractApplyResponse;
import com.insur.insurcoresystem.service.contract.dto.ContractInquiryResponse;
import com.insur.insurcoresystem.service.contract.dto.ContractEndorseResponse;
import com.insur.insurcoresystem.service.contract.dto.ContractListResponse;
import com.insur.insurcoresystem.service.contract.dto.EndorsementHistoryPageResponse;
import com.insur.insurcoresystem.service.contract.dto.ContractStatusChangeResponse;
import com.insur.insurcoresystem.service.contract.dto.StatusHistoryPageResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * 역할별 접근 권한 검증. API 별 허용 역할(URL 규칙)과 상태 변경의 상태별 권한(ContractAccessPolicy)을 확인한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class RoleAuthorizationTest {

    @Autowired
    MockMvc mockMvc;
    @MockitoBean
    ContractService contractService;
    @MockitoBean
    ContractInquiryService inquiryService;
    @MockitoBean
    ContractStatusService statusService;
    @MockitoBean
    PremiumService premiumService;
    @MockitoBean
    ContractEndorsementService endorsementService;

    @Test
    void 청약_접수는_AGENT와_ADMIN만_가능하다() throws Exception {
        given(contractService.applyContract(any(), any(), any())).willReturn(ContractApplyResponse.builder().build());

        call("/api/contract/apply", "{}", TestAuth.withRoles(Roles.AGENT)).andExpect(status().isOk());
        call("/api/contract/apply", "{}", TestAuth.withRoles(Roles.ADMIN)).andExpect(status().isOk());

        for (String role : new String[] {Roles.UNDERWRITER, Roles.OPERATOR}) {
            denied(call("/api/contract/apply", "{}", TestAuth.withRoles(role)));
        }
        denied(call("/api/contract/apply", "{}", TestAuth.withRoles()));   // 역할이 없는 토큰
    }

    @Test
    void 계약_조회는_모든_업무_역할이_가능하고_역할이_없으면_거절된다() throws Exception {
        given(inquiryService.inquireContract(any(), any())).willReturn(ContractInquiryResponse.builder().build());

        for (String role : new String[] {Roles.AGENT, Roles.UNDERWRITER, Roles.OPERATOR, Roles.ADMIN}) {
            call("/api/contract/inquiry", "{\"contractNo\":\"POL-1\"}", TestAuth.withRoles(role))
                    .andExpect(status().isOk());
        }
        denied(call("/api/contract/inquiry", "{\"contractNo\":\"POL-1\"}", TestAuth.withRoles()));
    }

    @Test
    void 조회_범위는_AGENT만_가진_사용자만_본인_접수_계약으로_제한된다() throws Exception {
        given(inquiryService.inquireContract(any(), any())).willReturn(ContractInquiryResponse.builder().build());
        String body = "{\"contractNo\":\"POL-1\"}";

        call("/api/contract/inquiry", body, TestAuth.as("E100", Roles.AGENT)).andExpect(status().isOk());
        verify(inquiryService).inquireContract(eq("E100"), any());   // AGENT: 본인 사번으로 제한

        for (String[] roles : new String[][] {{Roles.UNDERWRITER}, {Roles.OPERATOR}, {Roles.ADMIN},
                {Roles.AGENT, Roles.UNDERWRITER}}) {
            call("/api/contract/inquiry", body, TestAuth.as("E200", roles)).andExpect(status().isOk());
        }
        verify(inquiryService, times(4)).inquireContract(isNull(), any());   // 그 외 역할: 제한 없음
    }

    @Test
    void 계약_목록_조회는_조회와_같은_역할과_조회_범위를_따른다() throws Exception {
        given(inquiryService.listContracts(any(), any())).willReturn(ContractListResponse.builder().build());

        call("/api/contract/list", "{}", TestAuth.as("E100", Roles.AGENT)).andExpect(status().isOk());
        verify(inquiryService).listContracts(eq("E100"), any());   // AGENT: 본인이 접수한 계약만
        for (String role : new String[] {Roles.UNDERWRITER, Roles.OPERATOR, Roles.ADMIN}) {
            call("/api/contract/list", "{}", TestAuth.as("E200", role)).andExpect(status().isOk());
        }
        verify(inquiryService, times(3)).listContracts(isNull(), any());
        denied(call("/api/contract/list", "{}", TestAuth.withRoles()));   // 역할이 없는 토큰
    }

    @Test
    void 보험료_산출은_모든_업무_역할이_가능하고_역할이_없으면_거절된다() throws Exception {
        given(premiumService.quote(any())).willReturn(PremiumQuoteResponse.builder().build());

        for (String role : new String[] {Roles.AGENT, Roles.UNDERWRITER, Roles.OPERATOR, Roles.ADMIN}) {
            call("/api/premium/quote", "{}", TestAuth.withRoles(role)).andExpect(status().isOk());
        }
        denied(call("/api/premium/quote", "{}", TestAuth.withRoles()));
        verify(premiumService, times(4)).quote(any());
    }

    @Test
    void 계약_변경_배서는_심사_계약관리_ADMIN만_가능하다() throws Exception {
        given(endorsementService.endorse(any(), any(), any())).willReturn(ContractEndorseResponse.builder().build());

        for (String role : new String[] {Roles.UNDERWRITER, Roles.OPERATOR, Roles.ADMIN}) {
            call("/api/contract/endorse", "{}", TestAuth.withRoles(role)).andExpect(status().isOk());
        }
        denied(call("/api/contract/endorse", "{}", TestAuth.withRoles(Roles.AGENT)));
        denied(call("/api/contract/endorse", "{}", TestAuth.withRoles()));
        verify(endorsementService, times(3)).endorse(any(), any(), any());
    }

    @Test
    void 배서_이력_조회는_조회와_같은_역할과_조회_범위를_따른다() throws Exception {
        given(endorsementService.history(any(), any())).willReturn(EndorsementHistoryPageResponse.builder().build());

        call("/api/contract/endorse-history", "{}", TestAuth.as("E100", Roles.AGENT)).andExpect(status().isOk());
        verify(endorsementService).history(eq("E100"), any());   // AGENT: 본인 사번으로 제한
        for (String role : new String[] {Roles.UNDERWRITER, Roles.OPERATOR, Roles.ADMIN}) {
            call("/api/contract/endorse-history", "{}", TestAuth.as("E200", role)).andExpect(status().isOk());
        }
        verify(endorsementService, times(3)).history(isNull(), any());
        denied(call("/api/contract/endorse-history", "{}", TestAuth.withRoles()));
    }

    @Test
    void 상태_이력_조회는_조회와_같은_역할과_조회_범위를_따른다() throws Exception {
        given(inquiryService.inquireStatusHistory(any(), any()))
                .willReturn(StatusHistoryPageResponse.builder().build());
        String body = "{\"contractNo\":\"POL-1\",\"page\":0,\"size\":10}";

        call("/api/contract/status-history", body, TestAuth.as("E100", Roles.AGENT)).andExpect(status().isOk());
        verify(inquiryService).inquireStatusHistory(eq("E100"), any());   // AGENT: 본인 사번으로 제한
        for (String role : new String[] {Roles.UNDERWRITER, Roles.OPERATOR, Roles.ADMIN}) {
            call("/api/contract/status-history", body, TestAuth.as("E200", role)).andExpect(status().isOk());
        }
        verify(inquiryService, times(3)).inquireStatusHistory(isNull(), any());
        denied(call("/api/contract/status-history", body, TestAuth.withRoles()));   // 역할이 없는 토큰
    }

    @Test
    void 상태_변경은_변경할_상태에_맞는_역할만_가능하다() throws Exception {
        given(statusService.changeContractStatus(any(), any(), any()))
                .willReturn(ContractStatusChangeResponse.builder().build());

        // 승인(20), 청약취소(90): UNDERWRITER · ADMIN
        for (String target : new String[] {"20", "90"}) {
            call("/api/contract/status-change", statusBody(target), TestAuth.withRoles(Roles.UNDERWRITER))
                    .andExpect(status().isOk());
            call("/api/contract/status-change", statusBody(target), TestAuth.withRoles(Roles.ADMIN))
                    .andExpect(status().isOk());
            denied(call("/api/contract/status-change", statusBody(target), TestAuth.withRoles(Roles.OPERATOR)));
        }
        // 해지(30): OPERATOR · ADMIN
        call("/api/contract/status-change", statusBody("30"), TestAuth.withRoles(Roles.OPERATOR))
                .andExpect(status().isOk());
        call("/api/contract/status-change", statusBody("30"), TestAuth.withRoles(Roles.ADMIN))
                .andExpect(status().isOk());
        denied(call("/api/contract/status-change", statusBody("30"), TestAuth.withRoles(Roles.UNDERWRITER)));
    }

    @Test
    void 청약_접수_담당은_상태를_변경할_수_없다() throws Exception {
        denied(call("/api/contract/status-change", statusBody("20"), TestAuth.withRoles(Roles.AGENT)));

        verifyNoInteractions(contractService, inquiryService, statusService);
    }

    @Test
    void 권한이_없으면_서비스를_호출하지_않고_상태별_거절도_요청의_거래ID를_응답에_담는다() throws Exception {
        call("/api/contract/status-change", statusBody("30"), TestAuth.withRoles(Roles.UNDERWRITER))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.header.globalTxId").value("tx-role"))
                .andExpect(jsonPath("$.header.responseCode").value("E-AUTH-002"));

        verifyNoInteractions(contractService, inquiryService, statusService);
    }

    @Test
    void 모르는_상태코드는_권한이_아니라_입력_오류로_처리한다() throws Exception {
        given(statusService.changeContractStatus(any(), any(), any()))
                .willThrow(new com.insur.insurcoresystem.common.exception.BusinessException("E-CTR-013", "지원하지 않는 상태"));

        call("/api/contract/status-change", statusBody("99"), TestAuth.withRoles(Roles.UNDERWRITER))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.responseCode").value("E-CTR-013"));
    }

    private ResultActions call(String path, String body, RequestPostProcessor auth) throws Exception {
        String json = body.equals("{}")
                ? "{ \"header\": { \"globalTxId\": \"tx-role\" }, \"body\": {} }"
                : "{ \"header\": { \"globalTxId\": \"tx-role\" }, \"body\": " + body + " }";
        return mockMvc.perform(post(path).with(auth).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private static String statusBody(String target) {
        return "{\"contractNo\":\"POL-1\",\"ctrStatCd\":\"" + target + "\"}";
    }

    /** 403 과 E-AUTH-002 공통 전문 응답인지 확인한다. */
    private static void denied(ResultActions result) throws Exception {
        result.andExpect(status().isForbidden())
                .andExpect(jsonPath("$.header.responseCode").value("E-AUTH-002"))
                .andExpect(jsonPath("$.header.globalTxId").isNotEmpty());
    }
}

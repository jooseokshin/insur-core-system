package com.insur.insurcoresystem.common.exception;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.insur.insurcoresystem.service.contract.ContractService;
import org.springframework.http.HttpStatus;
import com.insur.insurcoresystem.common.security.TestAuth;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 시스템/프레임워크 오류가 공통 전문으로 감싸져 응답되는지 검증한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class GlobalExceptionHandlerTest {

    private static final String VALID_JSON = """
            {
              "header": { "globalTxId": "tx-9999", "empId": "E12345" },
              "body": {
                "insrClsCd": "01",
                "totalPremium": 100,
                "coverages": [ { "cvrgCd": "CA001", "isrdAmt": 1000, "prem": 100 } ]
              }
            }
            """;

    @Autowired
    MockMvc mockMvc;
    @MockitoBean
    ContractService contractService;

    @Test
    void 예상하지_못한_예외는_E_SYS_999로_감싸고_내부_메시지를_노출하지_않는다() throws Exception {
        given(contractService.applyContract(any(), any(), any()))
                .willThrow(new IllegalStateException("connection refused: jdbc:postgresql://internal-db"));

        mockMvc.perform(post("/api/contract/apply").with(TestAuth.emp())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_JSON))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.header.globalTxId").value("tx-9999"))
                .andExpect(jsonPath("$.header.empId").value("E12345"))
                .andExpect(jsonPath("$.header.responseCode").value("E-SYS-999"))
                .andExpect(jsonPath("$.header.responseMsg").value(not(containsString("jdbc"))));
    }

    @Test
    void 업무예외는_지정한_HTTP_상태로_응답하고_업무_오류코드와_메시지를_그대로_담는다() throws Exception {
        given(contractService.applyContract(any(), any(), any()))
                .willThrow(new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "E-SEQ-002", "채번 한도를 초과했습니다"));

        mockMvc.perform(post("/api/contract/apply").with(TestAuth.emp())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_JSON))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.header.responseCode").value("E-SEQ-002"))
                .andExpect(jsonPath("$.header.responseMsg").value("채번 한도를 초과했습니다"));
    }

    @Test
    void 상태를_지정하지_않은_업무예외는_400으로_응답한다() throws Exception {
        given(contractService.applyContract(any(), any(), any()))
                .willThrow(new BusinessException("E-CTR-003", "보험료 불일치"));

        mockMvc.perform(post("/api/contract/apply").with(TestAuth.emp())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.responseCode").value("E-CTR-003"));
    }

    @Test
    void 잘못된_JSON은_E_CMN_002로_응답한다() throws Exception {
        mockMvc.perform(post("/api/contract/apply").with(TestAuth.emp())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"header\": { "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.globalTxId").isNotEmpty())
                .andExpect(jsonPath("$.header.responseCode").value("E-CMN-002"));
    }

    @Test
    void 존재하지_않는_경로는_E_CMN_003으로_응답한다() throws Exception {
        mockMvc.perform(post("/api/contract/unknown").with(TestAuth.emp())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_JSON))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.header.responseCode").value("E-CMN-003"));
    }

    @Test
    void 허용되지_않은_메서드는_E_CMN_004로_응답한다() throws Exception {
        mockMvc.perform(get("/api/contract/apply").with(TestAuth.emp()))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.header.responseCode").value("E-CMN-004"));
    }

    @Test
    void 지원하지_않는_Content_Type은_E_CMN_005로_응답한다() throws Exception {
        mockMvc.perform(post("/api/contract/apply").with(TestAuth.emp())
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("hello"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.header.responseCode").value("E-CMN-005"));
    }
}

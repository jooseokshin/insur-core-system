package com.insur.insurcoresystem.ext.cms;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 모의 금융결제원 CMS (개발·테스트 전용, {@code app.ext.cms.mode=simulator}, 기본값). 의뢰 파일을 받으면 은행 계좌(TB_SIM_BANK_ACCOUNT)를 보고
 * 바로 결과 파일을 만들어 둔다(TB_SIM_CMS_FILE). 실제로는 결과가 다음 영업일에 온다.
 * <ul>
 *     <li>등록(EB11): 없는 계좌 B101, 해지 B102, 지급정지 B103, 예금주가 다르면 C201</li>
 *     <li>출금(EB21): 등록되지 않은 납부자·계좌 C302, 해지·지급정지, 잔액이 모자라면 C301 (잔액을 줄인다)</li>
 *     <li>{@link #setResultDelayed(boolean)}: 결과 파일이 아직 오지 않은 상태를 흉내 낸다 (배치 재시작 확인용)</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(name = "app.ext.cms.mode", havingValue = "simulator", matchIfMissing = true)
public class KftcCmsSimulator implements CmsFileTransport {

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final AtomicBoolean resultDelayed = new AtomicBoolean(false);

    public KftcCmsSimulator(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public void setResultDelayed(boolean delayed) {
        resultDelayed.set(delayed);
    }

    @Override
    public void send(String fileType, LocalDate fileDate, String content) {
        CmsFileCodec.CmsFile file = CmsFileCodec.parse(fileType, fileDate, content);
        List<Map<String, Object>> results = new ArrayList<>();
        String resultType;
        if (CmsFileCodec.EB11.equals(fileType)) {
            resultType = CmsFileCodec.EB12;
            for (Map<String, String> r : file.records()) {
                Map<String, Object> account = account(r.get("bankCd"), r.get("accountNo"));
                String code = accountCode(account);
                if (CmsFileCodec.OK.equals(code) && !((String) account.get("holder_nm")).replaceAll("\\s", "").equals(r.get("payerNm").replaceAll("\\s", ""))) {
                    code = "C201";
                }
                if (CmsFileCodec.OK.equals(code)) {
                    jdbc.update("""
                            INSERT INTO TB_SIM_CMS_MANDATE (ORG_CODE, PAYER_NO, BANK_CD, ACCOUNT_NO, REG_DT) VALUES (?, ?, ?, ?, ?)
                            ON CONFLICT (ORG_CODE, PAYER_NO) DO UPDATE SET BANK_CD = EXCLUDED.BANK_CD, ACCOUNT_NO = EXCLUDED.ACCOUNT_NO, REG_DT = EXCLUDED.REG_DT
                            """, file.orgCode(), r.get("payerNo"), r.get("bankCd"), r.get("accountNo"), LocalDateTime.now(clock));
                }
                results.add(Map.of("payerNo", r.get("payerNo"), "resultCd", code));
            }
        } else if (CmsFileCodec.EB21.equals(fileType)) {
            resultType = CmsFileCodec.EB22;
            for (Map<String, String> r : file.records()) {
                long amount = Long.parseLong(r.get("amount"));
                List<Map<String, Object>> mandate = jdbc.queryForList(
                        "SELECT 1 FROM TB_SIM_CMS_MANDATE WHERE ORG_CODE = ? AND PAYER_NO = ? AND BANK_CD = ? AND ACCOUNT_NO = ?",
                        file.orgCode(), r.get("payerNo"), r.get("bankCd"), r.get("accountNo"));
                Map<String, Object> account = account(r.get("bankCd"), r.get("accountNo"));
                String code = mandate.isEmpty() ? "C302" : accountCode(account);
                if (CmsFileCodec.OK.equals(code) && ((Number) account.get("balance")).longValue() < amount) {
                    code = CmsFileCodec.INSUFFICIENT;
                }
                long debited = 0;
                if (CmsFileCodec.OK.equals(code)) {
                    jdbc.update("UPDATE TB_SIM_BANK_ACCOUNT SET BALANCE = BALANCE - ? WHERE BANK_CD = ? AND ACCOUNT_NO = ?",
                            amount, r.get("bankCd"), r.get("accountNo"));
                    debited = amount;
                }
                Map<String, Object> row = new HashMap<>();
                row.put("payerNo", r.get("payerNo"));
                row.put("amount", amount);
                row.put("debited", debited);
                row.put("resultCd", code);
                results.add(row);
            }
        } else {
            throw new IllegalArgumentException("의뢰 파일이 아닙니다: " + fileType);
        }
        jdbc.update("""
                INSERT INTO TB_SIM_CMS_FILE (FILE_TYPE, FILE_DT, CONTENT, CREATED_DT) VALUES (?, ?, ?, ?)
                ON CONFLICT (FILE_TYPE, FILE_DT) DO UPDATE SET CONTENT = EXCLUDED.CONTENT, CREATED_DT = EXCLUDED.CREATED_DT
                """, resultType, fileDate, CmsFileCodec.build(resultType, fileDate, file.orgCode(), results), LocalDateTime.now(clock));
    }

    @Override
    public Optional<String> receive(String fileType, LocalDate fileDate) {
        if (resultDelayed.get()) {
            return Optional.empty();
        }
        return jdbc.queryForList("SELECT CONTENT FROM TB_SIM_CMS_FILE WHERE FILE_TYPE = ? AND FILE_DT = ?", String.class, fileType, fileDate)
                .stream().findFirst();
    }

    private Map<String, Object> account(String bankCd, String accountNo) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT HOLDER_NM, STAT_CD, BALANCE FROM TB_SIM_BANK_ACCOUNT WHERE BANK_CD = ? AND ACCOUNT_NO = ?", bankCd, accountNo);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static String accountCode(Map<String, Object> account) {
        if (account == null) {
            return "B101";
        }
        return switch ((String) account.get("stat_cd")) {
            case "CLOSED" -> "B102";
            case "FROZEN" -> "B103";
            default -> CmsFileCodec.OK;
        };
    }
}

package com.insur.insurcoresystem.service.payout;

import static com.insur.insurcoresystem.service.contract.ContractSupport.isConstraintViolation;
import static com.insur.insurcoresystem.service.contract.ContractSupport.normalizeEmpId;
import static com.insur.insurcoresystem.service.contract.ContractSupport.validateGlobalTxId;

import com.insur.insurcoresystem.common.exception.BusinessException;
import com.insur.insurcoresystem.common.security.AccountCipher;
import com.insur.insurcoresystem.ext.ExternalAgencyException;
import com.insur.insurcoresystem.ext.TraceNumbers;
import com.insur.insurcoresystem.ext.bank.BankClient;
import com.insur.insurcoresystem.ext.bank.BankLayouts;
import com.insur.insurcoresystem.service.payout.dto.PayoutRequests;
import com.insur.insurcoresystem.service.payout.dto.PayoutResponses;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 보험금·마일리지 환급금 지급 요청 (은행 펌뱅킹). 요청은 지급 원장(TB_PAY_OUT)에 대기(10)로 쌓고, 이체는 {@link PayoutSender} 가 한다.
 * <ul>
 *     <li>지급액은 원천에서 가져온다(사고: 지급 결정(40)된 지급액 합계, 마일리지: 정산(20)된 환급액). 화면이 금액을 정하지 않는다.</li>
 *     <li>요청할 때 은행에 예금주 조회를 해서 계좌가 정상이고 예금주가 입력한 이름과 같아야 받는다. 잘못된 계좌로 보낸 돈은 돌려받기 어렵다(착오 송금).</li>
 *     <li>원천당 실패(80)하지 않은 지급은 하나만 있다(부분 유니크 인덱스). 실패하면 계좌를 고쳐 새로 요청한다.</li>
 *     <li>계좌번호는 암호화해 저장하고 화면·대사에는 마스킹 값만 쓴다.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PayoutService {

    public static final String SRC_CLAIM = "CLAIM";
    public static final String SRC_MILEAGE = "MILEAGE";
    private static final String ACTIVE_SRC_INDEX = "uk_pay_out_active_src";
    private static final String GLOBAL_TX_ID_CONSTRAINT = "uk_pay_out_global_tx_id";

    private final NamedParameterJdbcTemplate jdbc;
    private final BankClient bank;
    private final AccountCipher cipher;
    private final TraceNumbers traceNumbers;
    private final Clock clock;

    /**
     * 예금주를 조회한다 (지급 요청 전 확인용, 아무것도 저장하지 않음).
     *
     * @throws BusinessException 입력 오류(E-PAY-001), 은행 장애(E-PAY-005, 503)
     */
    public PayoutResponses.VerifyResult verify(PayoutRequests.Verify request) {
        validateAccount(request.getBankCd(), request.getAccountNo(), request.getHolderNm());
        BankClient.VerifyResult result = callVerify(request.getBankCd(), request.getAccountNo());
        boolean matched = result.ok() && sameName(result.holderNm(), request.getHolderNm());
        return PayoutResponses.VerifyResult.builder()
                .matched(matched)
                .resultCd(result.code())
                .resultMsg(!result.ok() ? BankLayouts.describe(result.code()) : matched ? "예금주 일치" : "예금주가 다릅니다")
                .bankHolderNm(result.holderNm() == null ? null : maskName(result.holderNm()))
                .build();
    }

    /**
     * 지급을 요청한다. 같은 거래 UUID 의 재전송은 처음 결과를 돌려준다.
     *
     * @throws BusinessException 입력 오류(E-PAY-001), 지급할 수 없는 원천(E-PAY-002), 계좌 오류·예금주 불일치(E-PAY-003),
     *                           이미 진행 중인 지급(E-PAY-004, 409), 은행 장애(E-PAY-005, 503), 같은 거래 UUID 로 다른 요청(E-PAY-007, 409),
     *                           암호화 키 없음(E-PAY-009, 503)
     */
    @Transactional
    public PayoutResponses.Payout request(String globalTxId, String empId, PayoutRequests.Request request) {
        validateGlobalTxId(globalTxId);
        String srcType = request.getSrcType();
        if (!SRC_CLAIM.equals(srcType) && !SRC_MILEAGE.equals(srcType) || !StringUtils.hasText(request.getSrcNo())) {
            throw new BusinessException("E-PAY-001", "지급 원천은 CLAIM 또는 MILEAGE 이고 원천 번호가 있어야 합니다.");
        }
        validateAccount(request.getBankCd(), request.getAccountNo(), request.getHolderNm());

        Optional<PayoutResponses.Payout> replay = findOne("GLOBAL_TX_ID = :v", globalTxId);
        if (replay.isPresent()) {
            if (!replay.get().getSrcType().equals(srcType) || !replay.get().getSrcNo().equals(request.getSrcNo())) {
                throw new BusinessException(HttpStatus.CONFLICT, "E-PAY-007", "같은 거래 UUID(globalTxId)로 다른 지급 요청이 들어왔습니다.");
            }
            return replay.get();
        }

        Map<String, Object> source = source(srcType, request.getSrcNo());
        if (activeExists(srcType, request.getSrcNo())) {
            throw new BusinessException(HttpStatus.CONFLICT, "E-PAY-004", "이미 진행 중이거나 완료된 지급이 있습니다. (실패한 지급만 다시 요청할 수 있습니다)");
        }
        BankClient.VerifyResult verified = callVerify(request.getBankCd(), request.getAccountNo());
        if (!verified.ok()) {
            throw new BusinessException("E-PAY-003", "지급할 수 없는 계좌입니다: " + BankLayouts.describe(verified.code()));
        }
        if (!sameName(verified.holderNm(), request.getHolderNm())) {
            throw new BusinessException("E-PAY-003", "예금주가 다릅니다. (은행 예금주 " + maskName(verified.holderNm()) + ")");
        }

        LocalDateTime now = LocalDateTime.now(clock);
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("srcType", srcType).addValue("srcNo", request.getSrcNo())
                .addValue("contractNo", source.get("contract_no")).addValue("amount", source.get("amount"))
                .addValue("bankCd", request.getBankCd()).addValue("enc", cipher.encrypt(request.getAccountNo()))
                .addValue("mask", AccountCipher.mask(request.getAccountNo())).addValue("holder", verified.holderNm().trim())
                .addValue("trace", traceNumbers.next()).addValue("now", now).addValue("emp", normalizeEmpId(empId))
                .addValue("tx", globalTxId);
        try {
            jdbc.update("""
                    INSERT INTO TB_PAY_OUT (SRC_TYPE, SRC_NO, CONTRACT_NO, PAY_AMT, BANK_CD, ACCOUNT_ENC, ACCOUNT_MASK, HOLDER_NM, STAT_CD,
                                            TRACE_NO, NEXT_TRY_DT, REQ_EMP_ID, REQ_DT, GLOBAL_TX_ID)
                    VALUES (:srcType, :srcNo, :contractNo, :amount, :bankCd, :enc, :mask, :holder, '10', :trace, :now, :emp, :now, :tx)
                    """, params);
        } catch (DataIntegrityViolationException e) {
            if (isConstraintViolation(e, ACTIVE_SRC_INDEX) || isConstraintViolation(e, GLOBAL_TX_ID_CONSTRAINT)) {
                throw new BusinessException(HttpStatus.CONFLICT, "E-PAY-004", "같은 원천의 지급이 동시에 요청되었습니다. 지급 목록을 확인해 주세요.");
            }
            throw e;
        }
        log.info("지급 요청: 원천={} {}, 금액={}, 계좌={} {}", srcType, request.getSrcNo(), source.get("amount"), request.getBankCd(),
                AccountCipher.mask(request.getAccountNo()));
        return findOne("GLOBAL_TX_ID = :v", globalTxId).orElseThrow();
    }

    /**
     * 지급 목록을 최근순으로 조회한다.
     *
     * @throws BusinessException 조건 오류(E-PAY-001)
     */
    @Transactional(readOnly = true)
    public List<PayoutResponses.Payout> list(PayoutRequests.ListCond cond) {
        if (StringUtils.hasText(cond.getStatCd()) && !List.of("10", "30", "70", "80").contains(cond.getStatCd())) {
            throw new BusinessException("E-PAY-001", "지급 상태는 10, 30, 70, 80 중 하나여야 합니다: " + cond.getStatCd());
        }
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        MapSqlParameterSource p = new MapSqlParameterSource("limit", cond.getLimit() == null ? 50 : Math.max(1, Math.min(cond.getLimit(), 200)));
        if (StringUtils.hasText(cond.getSrcType())) {
            where.append(" AND SRC_TYPE = :srcType");
            p.addValue("srcType", cond.getSrcType());
        }
        if (StringUtils.hasText(cond.getSrcNo())) {
            where.append(" AND SRC_NO = :srcNo");
            p.addValue("srcNo", cond.getSrcNo());
        }
        if (StringUtils.hasText(cond.getContractNo())) {
            where.append(" AND CONTRACT_NO = :contractNo");
            p.addValue("contractNo", cond.getContractNo());
        }
        if (StringUtils.hasText(cond.getStatCd())) {
            where.append(" AND STAT_CD = :stat");
            p.addValue("stat", cond.getStatCd());
        }
        return jdbc.query("SELECT * FROM TB_PAY_OUT" + where + " ORDER BY PAYOUT_ID DESC LIMIT :limit", p, MAPPER);
    }

    static final RowMapper<PayoutResponses.Payout> MAPPER = (rs, i) -> PayoutResponses.Payout.builder()
            .payoutId(rs.getLong("PAYOUT_ID"))
            .srcType(rs.getString("SRC_TYPE"))
            .srcNo(rs.getString("SRC_NO"))
            .contractNo(rs.getString("CONTRACT_NO"))
            .payAmt(rs.getBigDecimal("PAY_AMT"))
            .bankCd(rs.getString("BANK_CD"))
            .accountMask(rs.getString("ACCOUNT_MASK"))
            .holderNm(rs.getString("HOLDER_NM"))
            .statCd(rs.getString("STAT_CD"))
            .traceNo(rs.getString("TRACE_NO"))
            .sendCount(rs.getInt("SEND_COUNT"))
            .inquiryCount(rs.getInt("INQUIRY_COUNT"))
            .nextTryDt(rs.getObject("NEXT_TRY_DT", LocalDateTime.class))
            .resultCd(rs.getString("RESULT_CD"))
            .resultMsg(rs.getString("RESULT_MSG"))
            .reqEmpId(rs.getString("REQ_EMP_ID"))
            .reqDt(rs.getObject("REQ_DT", LocalDateTime.class))
            .sentDt(rs.getObject("SENT_DT", LocalDateTime.class))
            .doneDt(rs.getObject("DONE_DT", LocalDateTime.class))
            .build();

    private Optional<PayoutResponses.Payout> findOne(String condition, Object value) {
        return jdbc.query("SELECT * FROM TB_PAY_OUT WHERE " + condition, new MapSqlParameterSource("v", value), MAPPER).stream().findFirst();
    }

    /** 원천의 증권번호와 지급액. 지급할 수 없으면 E-PAY-002 */
    private Map<String, Object> source(String srcType, String srcNo) {
        List<Map<String, Object>> rows = SRC_CLAIM.equals(srcType)
                ? jdbc.queryForList("SELECT CONTRACT_NO, TOTAL_PAY_AMT AS AMOUNT FROM TB_CLM WHERE CLM_NO = :no AND CLM_STAT_CD = '40' AND TOTAL_PAY_AMT > 0",
                        new MapSqlParameterSource("no", srcNo))
                : jdbc.queryForList("SELECT CONTRACT_NO, REFUND_AMT AS AMOUNT FROM TB_CTR_MILEAGE WHERE CONTRACT_NO = :no AND MIL_STAT_CD = '20' AND REFUND_AMT > 0",
                        new MapSqlParameterSource("no", srcNo));
        if (rows.isEmpty()) {
            throw new BusinessException("E-PAY-002", SRC_CLAIM.equals(srcType)
                    ? "지급 결정(40)되고 지급액이 있는 사고만 지급할 수 있습니다: " + srcNo
                    : "정산(20)되고 환급액이 있는 마일리지 특약만 지급할 수 있습니다: " + srcNo);
        }
        Map<String, Object> row = rows.get(0);
        return Map.of("contract_no", row.get("contract_no"), "amount", (BigDecimal) row.get("amount"));
    }

    private boolean activeExists(String srcType, String srcNo) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM TB_PAY_OUT WHERE SRC_TYPE = :t AND SRC_NO = :n AND STAT_CD <> '80'",
                new MapSqlParameterSource("t", srcType).addValue("n", srcNo), Integer.class);
        return n != null && n > 0;
    }

    private BankClient.VerifyResult callVerify(String bankCd, String accountNo) {
        try {
            return bank.verify(bankCd, accountNo);
        } catch (ExternalAgencyException e) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "E-PAY-005", "은행 예금주 조회를 하지 못했습니다. 잠시 후 다시 해 주세요. (" + e.getMessage() + ")");
        }
    }

    private static void validateAccount(String bankCd, String accountNo, String holderNm) {
        if (bankCd == null || !bankCd.matches("\\d{3}") || accountNo == null || !accountNo.matches("\\d{10,16}")
                || !StringUtils.hasText(holderNm) || holderNm.trim().length() > 20) {
            throw new BusinessException("E-PAY-001", "은행 코드(숫자 3자리), 계좌번호(숫자 10~16자리), 예금주(20자 이하)를 입력해야 합니다.");
        }
    }

    /** 공백을 빼고 비교한다 (은행 예금주는 고정길이라 뒤에 공백이 붙어 온다) */
    static boolean sameName(String bankName, String input) {
        return bankName != null && bankName.replaceAll("\\s", "").equals(input.replaceAll("\\s", ""));
    }

    /** 가운데 글자를 가린다: 홍길동 → 홍*동, 김철 → 김* */
    static String maskName(String name) {
        String n = name.trim();
        if (n.length() <= 1) {
            return n;
        }
        if (n.length() == 2) {
            return n.charAt(0) + "*";
        }
        return n.charAt(0) + "*".repeat(n.length() - 2) + n.charAt(n.length() - 1);
    }
}

package com.insur.insurcoresystem.ext.fep;

import com.insur.insurcoresystem.mapper.ext.FepMapper;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 전문 로그(TB_FEP_MSG_LOG)를 별도 트랜잭션으로 남긴다. 업무가 롤백돼도 기관에는 이미 전문이 갔으므로 로그는 남아야 한다.
 * 본문에는 계좌번호·이름 같은 개인정보가 있어 저장하지 않고 길이와 SHA-256 만 남긴다.
 */
@Component
@RequiredArgsConstructor
public class FepMessageLogger {

    private final FepMapper fepMapper;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void log(String instCd, String txType, String traceNo, LocalDateTime reqDt, long elapsedMs, String result,
                    String responseCode, byte[] request, Integer resBytes, String error) {
        fepMapper.insertMessageLog(FepMapper.MessageLogRow.builder()
                .instCd(instCd).txType(txType).traceNo(traceNo).reqDt(reqDt)
                .elapsedMs((int) Math.min(elapsedMs, Integer.MAX_VALUE)).resultCd(result).responseCd(responseCode)
                .reqBytes(request.length).resBytes(resBytes).reqHash(sha256(request))
                .errorMsg(error == null ? null : error.length() > 200 ? error.substring(0, 200) : error)
                .build());
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

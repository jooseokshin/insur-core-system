package com.insur.insurcoresystem.service.sequence;

import com.insur.insurcoresystem.common.exception.BusinessException;
import java.time.Clock;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 증권번호/청약번호 채번기
 * <p>
 * 형식: {POL|APP}-{상품구분}-{YYYY}-{6자리 일련번호} (예: POL-CAR-2026-000001)
 * <p>
 * 일련번호는 TB_CMN_SEQ 에서 (번호유형, 상품구분, 연도) 단위로 관리되며, 연도가 바뀌면 1부터 다시 시작한다.
 * UPSERT 한 문장으로 증가시키므로 동시 요청에도 중복 번호가 발급되지 않는다.
 * 호출자 트랜잭션에 참여하므로 청약이 롤백되면 채번도 함께 롤백되어 결번이 생기지 않는다.
 */
@Service
@RequiredArgsConstructor
public class ContractNumberGenerator {

    static final String SEQ_TYPE_POLICY = "POL";
    static final String SEQ_TYPE_APPLICATION = "APP";
    private static final long MAX_SERIAL = 999_999L;

    private static final String NEXT_SEQ_SQL = """
            INSERT INTO TB_CMN_SEQ (SEQ_TYPE, PROD_CD, SEQ_YEAR, LAST_NO)
            VALUES (?, ?, ?, 1)
            ON CONFLICT (SEQ_TYPE, PROD_CD, SEQ_YEAR)
            DO UPDATE SET LAST_NO = TB_CMN_SEQ.LAST_NO + 1
            RETURNING LAST_NO
            """;

    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    /**
     * 상품구분별로 증권번호와 청약번호를 한 쌍으로 채번한다. 연도는 업무 기준 시간대(Asia/Seoul)로 판단한다.
     * 호출자 트랜잭션에 참여하므로 호출자가 롤백되면 채번도 함께 롤백된다.
     *
     * @param productType 채번 대상 상품구분
     * @return 채번된 증권번호, 청약번호
     * @throws BusinessException 연간 일련번호 한도 초과 시(E-SEQ-002)
     */
    @Transactional
    public ContractNumbers generate(ProductType productType) {
        String year = String.valueOf(LocalDate.now(clock).getYear());
        return new ContractNumbers(
                next(SEQ_TYPE_POLICY, productType, year),
                next(SEQ_TYPE_APPLICATION, productType, year));
    }

    /**
     * 번호유형/상품구분/연도 단위의 다음 일련번호를 UPSERT 한 문장으로 증가시켜 "{번호유형}-{상품구분}-{연도}-{6자리 일련번호}" 형식으로 반환한다.
     *
     * @param seqType     번호유형 (POL: 증권번호, APP: 청약번호)
     * @param productType 상품구분
     * @param year        채번 연도 (YYYY)
     * @return 채번된 번호 (예: POL-CAR-2026-000001)
     * @throws BusinessException 일련번호가 최대값(999,999)을 초과한 경우(E-SEQ-002)
     */
    private String next(String seqType, ProductType productType, String year) {
        Long serial = jdbcTemplate.queryForObject(NEXT_SEQ_SQL, Long.class, seqType, productType.name(), year);
        if (serial == null || serial > MAX_SERIAL) {
            // 클라이언트가 고칠 수 없는 서버 측 한도 소진이므로 500 으로 응답한다.
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "E-SEQ-002",
                    "채번 한도를 초과했습니다: " + seqType + "-" + productType.name() + "-" + year);
        }
        return String.format("%s-%s-%s-%06d", seqType, productType.name(), year, serial);
    }
}

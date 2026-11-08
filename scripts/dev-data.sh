#!/bin/bash
# 개발용 가상 데이터 생성기. 손해율·업무 현황 같은 집계 기능을 의미 있는 양의 데이터로 확인하려고 쓴다.
# 만든 숫자는 실제 통계가 아니다. 사고 빈도와 손해액은 기능 검증을 위해 임의로 정한 값이다.
#
# 사용법: scripts/dev-data.sh [계약 건수=3000]     생성 (같은 시드라 매번 같은 데이터)
#         scripts/dev-data.sh --cleanup           생성한 데이터 삭제 (번호가 GEN- 로 시작하는 것만)
# 접속:   PGHOST(localhost) PGPORT(5432) PGUSER(postgres) PGPASSWORD(postgrespassword) PGDATABASE(insur_core_db)
#         로컬 개발 DB(localhost 의 insur_core_db)가 아니면 실행하지 않는다. 그래도 하려면 DEV_DATA_FORCE=1
set -euo pipefail

export PGHOST="${PGHOST:-localhost}" PGPORT="${PGPORT:-5432}" PGUSER="${PGUSER:-postgres}"
export PGPASSWORD="${PGPASSWORD:-postgrespassword}" PGDATABASE="${PGDATABASE:-insur_core_db}"

if [ "${DEV_DATA_FORCE:-}" != "1" ] && { [ "$PGHOST" != "localhost" ] && [ "$PGHOST" != "127.0.0.1" ] || [ "$PGDATABASE" != "insur_core_db" ]; }; then
  echo "로컬 개발 DB(localhost/insur_core_db)에서만 실행합니다. 현재: $PGHOST/$PGDATABASE (강제로 하려면 DEV_DATA_FORCE=1)" >&2
  exit 1
fi

PSQL=(psql -v ON_ERROR_STOP=1 -q -X)

if [ "${1:-}" = "--cleanup" ]; then
  refs=$("${PSQL[@]}" -tAc "SELECT COUNT(*) FROM TB_CTR_LGD WHERE RENEWED_FROM LIKE 'GEN-%' AND CONTRACT_NO NOT LIKE 'GEN-%'")
  if [ "$refs" != "0" ]; then
    echo "생성 데이터를 원 계약으로 갱신한 계약이 ${refs}건 있어 지우지 않습니다. 그 계약을 먼저 정리해 주세요." >&2
    exit 1
  fi
  "${PSQL[@]}" <<'SQL'
BEGIN;
DELETE FROM TB_CLM_HIST WHERE CLM_NO IN (SELECT CLM_NO FROM TB_CLM WHERE CONTRACT_NO LIKE 'GEN-%');
DELETE FROM TB_CLM_CVRG WHERE CLM_NO IN (SELECT CLM_NO FROM TB_CLM WHERE CONTRACT_NO LIKE 'GEN-%');
DELETE FROM TB_CLM WHERE CONTRACT_NO LIKE 'GEN-%';
DELETE FROM TB_CTR_PAYMENT WHERE CONTRACT_NO LIKE 'GEN-%';
DELETE FROM TB_CTR_STAT_HIST WHERE CONTRACT_NO LIKE 'GEN-%';
DELETE FROM TB_CTR_ENDORSE WHERE CONTRACT_NO LIKE 'GEN-%';
DELETE FROM TB_CTR_CVRG WHERE CONTRACT_NO LIKE 'GEN-%';
DELETE FROM TB_CTR_LGD WHERE CONTRACT_NO LIKE 'GEN-%';
COMMIT;
SQL
  echo "생성 데이터를 삭제했습니다."
  exit 0
fi

COUNT="${1:-3000}"
if ! [[ "$COUNT" =~ ^[0-9]+$ ]] || [ "$COUNT" -lt 1 ] || [ "$COUNT" -gt 100000 ]; then
  echo "계약 건수는 1~100000 사이 숫자여야 합니다: $COUNT" >&2
  exit 1
fi
if [ "$("${PSQL[@]}" -tAc "SELECT COUNT(*) FROM TB_CTR_LGD WHERE CONTRACT_NO LIKE 'GEN-%'")" != "0" ]; then
  echo "이미 생성한 데이터가 있습니다. 먼저 scripts/dev-data.sh --cleanup 으로 지워 주세요." >&2
  exit 1
fi

"${PSQL[@]}" -v count="$COUNT" <<'SQL'
BEGIN;
\o /dev/null
SELECT setseed(0.42);
\o

-- 계약: 2025-01-01 ~ 오늘 사이 시작하는 1년 계약. 상품은 자동차 표준형 60%, 실속형 25%, 주택화재 15%
-- (2025년 시작 자동차 표준형은 판매가 끝난 2025형 상품으로 둔다)
CREATE TEMP TABLE G_CTR ON COMMIT DROP AS
SELECT n,
       'GEN-POL-' || LPAD(n::text, 8, '0') AS contract_no,
       'GEN-APP-' || LPAD(n::text, 8, '0') AS appl_no,
       start_date,
       (start_date + INTERVAL '1 year')::date AS end_date,
       CASE WHEN p < 0.60 THEN CASE WHEN start_date < DATE '2026-01-01' THEN 'CAR-STD-2025' ELSE 'CAR-STD-2026' END
            WHEN p < 0.85 THEN 'CAR-ECO-2026' ELSE 'FIRE-HOME-2026' END AS prd_cd,
       CASE WHEN random() < 0.5 THEN 'agent01' ELSE 'agent02' END AS reg_emp_id,
       random() AS r_term
  FROM (SELECT n, random() AS p,
               (DATE '2025-01-01' + (random() * (CURRENT_DATE - DATE '2025-01-01'))::int) AS start_date
          FROM generate_series(1, :count) AS n) s;

-- 실속형은 2026년 상품이라, 2025년 시작분은 표준형 2025로 바꾼다
UPDATE G_CTR SET prd_cd = 'CAR-STD-2025' WHERE prd_cd = 'CAR-ECO-2026' AND start_date < DATE '2026-01-01';
-- 주택화재도 2026년 상품이라, 2025년 시작분은 시작일을 2026년으로 옮긴다
UPDATE G_CTR SET start_date = DATE '2026-01-01' + (n % GREATEST(CURRENT_DATE - DATE '2026-01-01', 1)),
                 end_date = (DATE '2026-01-01' + (n % GREATEST(CURRENT_DATE - DATE '2026-01-01', 1)) + INTERVAL '1 year')::date
 WHERE prd_cd = 'FIRE-HOME-2026' AND start_date < DATE '2026-01-01';

-- 담보: 상품 구성대로 (필수 담보는 항상, 특약은 확률로). 보험료 = 가입금액 × 시드 요율 (계수 1.0, 1년)
CREATE TEMP TABLE G_CVRG ON COMMIT DROP AS
SELECT contract_no, cvrg_cd, isrd_amt, ROUND(isrd_amt * rate) AS prem FROM (
  SELECT c.contract_no, x.cvrg_cd, x.isrd_amt, x.rate
    FROM G_CTR c
    CROSS JOIN LATERAL (VALUES
      ('CA001', CASE WHEN c.prd_cd = 'CAR-ECO-2026' THEN 50000000 ELSE 100000000 END::numeric, 0.005, 1.0),
      ('CA002', CASE WHEN c.prd_cd = 'CAR-ECO-2026' THEN 20000000 ELSE (2 + FLOOR(random() * 9)) * 10000000 END::numeric, 0.0175, 1.0),
      ('CA003', CASE WHEN c.prd_cd = 'CAR-ECO-2026' THEN 10000000 ELSE 30000000 END::numeric, 0.008,
       CASE WHEN c.prd_cd = 'CAR-STD-2025' THEN 0 ELSE 0.5 END),   -- 2025형에는 자기신체사고가 없다
      ('CA004', (1 + FLOOR(random() * 5)) * 10000000::numeric, 0.03, CASE WHEN c.prd_cd = 'CAR-STD-2026' THEN 0.6 ELSE 0 END),
      ('FI001', (1 + FLOOR(random() * 10)) * 100000000::numeric, 0.0012, 1.0),
      ('FI002', (1 + FLOOR(random() * 10)) * 10000000::numeric, 0.002, 0.6),
      ('FI003', 50000000::numeric, 0.0035, 0.4)
    ) AS x(cvrg_cd, isrd_amt, rate, p_take)
   WHERE (c.prd_cd LIKE 'CAR-%' AND x.cvrg_cd LIKE 'CA%' OR c.prd_cd LIKE 'FIRE-%' AND x.cvrg_cd LIKE 'FI%')
     AND random() < x.p_take
) t(contract_no, cvrg_cd, isrd_amt, rate);

-- 계약 원장: 10% 는 해지(해지일은 시작~min(종기, 오늘) 사이), 나머지는 유효. 모두 완납 후 승인된 것으로 둔다
INSERT INTO TB_CTR_LGD (CONTRACT_NO, APPL_NO, INSR_CLS_CD, PRD_CD, CTR_STAT_CD, START_DATE, END_DATE, TERM_DT, REFUND_PREM,
                        POLICYHOLDER_ID, INSURED_ID, TOTAL_PREMIUM, SYS_REG_DT, REG_EMP_ID, DRIVER_AGE, DISCOUNT_GRADE, STRUCTURE_GRADE,
                        GLOBAL_TX_ID)
SELECT c.contract_no, c.appl_no, CASE WHEN c.prd_cd LIKE 'CAR-%' THEN '01' ELSE '02' END, c.prd_cd,
       CASE WHEN c.r_term < 0.10 THEN '30' ELSE '20' END,
       c.start_date, c.end_date,
       CASE WHEN c.r_term < 0.10 THEN c.start_date + 1 + (c.r_term * 10 * GREATEST(LEAST(c.end_date, CURRENT_DATE) - c.start_date - 1, 0))::int END,
       NULL,
       'GEN-C' || LPAD(c.n::text, 6, '0'), 'GEN-C' || LPAD(c.n::text, 6, '0'),
       (SELECT COALESCE(SUM(v.prem), 0) FROM G_CVRG v WHERE v.contract_no = c.contract_no),
       (c.start_date - 7)::timestamp + TIME '10:00', c.reg_emp_id,
       CASE WHEN c.prd_cd LIKE 'CAR-%' THEN 35 END, CASE WHEN c.prd_cd LIKE 'CAR-%' THEN '3' END,
       CASE WHEN c.prd_cd LIKE 'FIRE-%' THEN '2' END,
       'gen-apply-' || c.n
  FROM G_CTR c;

-- 해지 환급보험료 (총보험료 × 남은 일수 ÷ 보험기간 일수)
UPDATE TB_CTR_LGD SET REFUND_PREM = ROUND(TOTAL_PREMIUM * GREATEST(END_DATE - TERM_DT, 0) / (END_DATE - START_DATE))
 WHERE CONTRACT_NO LIKE 'GEN-%' AND CTR_STAT_CD = '30';

INSERT INTO TB_CTR_CVRG (CONTRACT_NO, CVRG_CD, ISRD_AMT, PREM) SELECT contract_no, cvrg_cd, isrd_amt, prem FROM G_CVRG;

-- 납입(완납 1회)과 상태 이력(접수 → 승인 → 해지)
INSERT INTO TB_CTR_PAYMENT (CONTRACT_NO, PAY_AMT, PAY_METHOD, EMP_ID, GLOBAL_TX_ID, PAID_DT)
SELECT CONTRACT_NO, TOTAL_PREMIUM, 'TRANSFER', 'op01', 'gen-pay-' || CONTRACT_NO, SYS_REG_DT + INTERVAL '1 day'
  FROM TB_CTR_LGD WHERE CONTRACT_NO LIKE 'GEN-%' AND TOTAL_PREMIUM > 0;
INSERT INTO TB_CTR_STAT_HIST (CONTRACT_NO, BEFORE_STAT_CD, AFTER_STAT_CD, EMP_ID, GLOBAL_TX_ID, CHG_DT)
SELECT CONTRACT_NO, NULL, '10', REG_EMP_ID, 'gen-apply-h-' || CONTRACT_NO, SYS_REG_DT FROM TB_CTR_LGD WHERE CONTRACT_NO LIKE 'GEN-%'
UNION ALL
SELECT CONTRACT_NO, '10', '20', 'uw01', 'gen-approve-' || CONTRACT_NO, SYS_REG_DT + INTERVAL '2 day' FROM TB_CTR_LGD WHERE CONTRACT_NO LIKE 'GEN-%'
UNION ALL
SELECT CONTRACT_NO, '20', '30', 'op01', 'gen-term-' || CONTRACT_NO, TERM_DT + TIME '15:00' FROM TB_CTR_LGD
 WHERE CONTRACT_NO LIKE 'GEN-%' AND CTR_STAT_CD = '30';

-- 사고: 담보마다 연간 사고 확률(임의값)에 보장 기간 비율을 곱해 0~1건. 사고일은 보장 기간 안, 오늘 이전
CREATE TEMP TABLE G_CLM ON COMMIT DROP AS
SELECT ROW_NUMBER() OVER (ORDER BY l.CONTRACT_NO, v.CVRG_CD) AS k, l.CONTRACT_NO, l.INSURED_ID, v.CVRG_CD, v.ISRD_AMT,
       acdt, ROUND((f.sev_min + random() * (f.sev_max - f.sev_min)) / 1000) * 1000 AS loss,
       COALESCE(pc.DEDUCTIBLE_AMT, 0) AS deductible, random() AS r_stat
  FROM TB_CTR_LGD l
  JOIN TB_CTR_CVRG v ON v.CONTRACT_NO = l.CONTRACT_NO
  JOIN (VALUES ('CA001', 0.02, 2000000, 20000000), ('CA002', 0.08, 300000, 3000000), ('CA003', 0.04, 500000, 5000000),
               ('CA004', 0.12, 300000, 4000000), ('FI001', 0.01, 5000000, 80000000), ('FI002', 0.03, 500000, 5000000),
               ('FI003', 0.01, 1000000, 10000000)) AS f(cvrg_cd, freq, sev_min, sev_max) ON f.cvrg_cd = v.CVRG_CD
  LEFT JOIN TB_PRD_CVRG pc ON pc.PRD_CD = l.PRD_CD AND pc.CVRG_CD = v.CVRG_CD
  CROSS JOIN LATERAL (SELECT LEAST(l.END_DATE, COALESCE(l.TERM_DT, l.END_DATE), CURRENT_DATE + 1) - l.START_DATE AS days) d
  CROSS JOIN LATERAL (SELECT l.START_DATE + (random() * GREATEST(d.days - 1, 0))::int AS acdt) a
 WHERE l.CONTRACT_NO LIKE 'GEN-%' AND d.days > 0 AND random() < f.freq * d.days / 365.0;

-- 상태: 60일 넘은 사고는 지급 85% · 면책 10% · 지급 대기 5%, 최근 사고는 접수 40% · 지급 대기 30% · 지급 30%
-- 지급액 = min(손해액 - 자기부담금, 가입금액). 같은 담보에 사고가 한 건이라 기지급 한도는 따로 보지 않는다
INSERT INTO TB_CLM (CLM_NO, CONTRACT_NO, CLM_STAT_CD, ACDT_DT, ACDT_DESC, CLAIMANT_ID, TOTAL_CLAIM_AMT, TOTAL_PAY_AMT, RPT_DT, RPT_EMP_ID,
                    ADJ_EMP_ID, ADJ_DT, DECIDE_EMP_ID, DECIDE_DT, DECIDE_MEMO, GLOBAL_TX_ID, REQ_HASH)
SELECT 'GEN-CLM-' || LPAD(k::text, 8, '0'), CONTRACT_NO, stat, acdt, '생성 데이터 사고 (' || CVRG_CD || ')', INSURED_ID,
       GREATEST(loss, 1000),
       CASE WHEN stat IN ('20', '40') THEN pay WHEN stat = '80' THEN 0 END,
       (acdt + 1)::timestamp + TIME '09:30', 'agent01',
       CASE WHEN stat IN ('20', '40') THEN 'clm01' END, CASE WHEN stat IN ('20', '40') THEN (acdt + 5)::timestamp + TIME '14:00' END,
       CASE WHEN stat IN ('40', '80') THEN 'clm01' END, CASE WHEN stat IN ('40', '80') THEN (acdt + 10)::timestamp + TIME '16:00' END,
       CASE WHEN stat = '80' THEN '약관상 보상하지 않는 손해 (생성 데이터)' END,
       'gen-clm-' || k, 'gen'
  FROM (SELECT g.*, LEAST(GREATEST(loss - deductible, 0), ISRD_AMT) AS pay,
               CASE WHEN CURRENT_DATE - acdt > 60 THEN CASE WHEN r_stat < 0.85 THEN '40' WHEN r_stat < 0.95 THEN '80' ELSE '20' END
                    ELSE CASE WHEN r_stat < 0.40 THEN '10' WHEN r_stat < 0.70 THEN '20' ELSE '40' END END AS stat
          FROM G_CLM g) s;

INSERT INTO TB_CLM_CVRG (CLM_NO, CVRG_CD, CLAIM_AMT, ASSESSED_AMT, DEDUCTIBLE_AMT, PAY_AMT)
SELECT c.CLM_NO, g.CVRG_CD, c.TOTAL_CLAIM_AMT,
       CASE WHEN c.CLM_STAT_CD IN ('20', '40') THEN c.TOTAL_CLAIM_AMT END,
       CASE WHEN c.CLM_STAT_CD IN ('20', '40') THEN g.deductible END,
       CASE WHEN c.CLM_STAT_CD IN ('20', '40', '80') THEN c.TOTAL_PAY_AMT END
  FROM TB_CLM c JOIN G_CLM g ON c.CLM_NO = 'GEN-CLM-' || LPAD(g.k::text, 8, '0');

INSERT INTO TB_CLM_HIST (CLM_NO, BEFORE_STAT_CD, AFTER_STAT_CD, EMP_ID, GLOBAL_TX_ID, CHG_DT)
SELECT CLM_NO, NULL, '10', RPT_EMP_ID, 'gen-h1-' || CLM_NO, RPT_DT FROM TB_CLM WHERE CLM_NO LIKE 'GEN-%'
UNION ALL
SELECT CLM_NO, '10', '20', ADJ_EMP_ID, 'gen-h2-' || CLM_NO, ADJ_DT FROM TB_CLM WHERE CLM_NO LIKE 'GEN-%' AND CLM_STAT_CD IN ('20', '40')
UNION ALL
SELECT CLM_NO, CASE WHEN CLM_STAT_CD = '40' THEN '20' ELSE '10' END, CLM_STAT_CD, DECIDE_EMP_ID, 'gen-h3-' || CLM_NO, DECIDE_DT
  FROM TB_CLM WHERE CLM_NO LIKE 'GEN-%' AND CLM_STAT_CD IN ('40', '80');

COMMIT;
SQL

"${PSQL[@]}" -tAc "SELECT '계약 ' || COUNT(*) || '건 (해지 ' || COUNT(*) FILTER (WHERE CTR_STAT_CD = '30') || '건), 사고 '
  || (SELECT COUNT(*) FROM TB_CLM WHERE CLM_NO LIKE 'GEN-%') || '건을 만들었습니다.' FROM TB_CTR_LGD WHERE CONTRACT_NO LIKE 'GEN-%'"
echo "지울 때: scripts/dev-data.sh --cleanup"

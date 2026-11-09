#!/bin/bash
# 개발용 가상 데이터 생성기. 손해율·업무 현황 같은 집계 기능을 의미 있는 양의 데이터로 확인하려고 쓴다.
# 만든 숫자는 실제 통계가 아니다. 사고 빈도와 손해액은 기능 검증을 위해 임의로 정한 값이다.
# 보험종목 네 가지(자동차·화재·운전자·해외여행)를 만들고, 담보 구성·가입금액 한도는 상품표(TB_PRD_CVRG)를,
# 요율과 계수는 요율표(TB_PRM_RATE, TB_PRM_FACTOR)를 보험시기 기준으로 읽어 보험료를 계산한다.
# 그래서 만든 보험료는 보험료 산출 API 가 같은 입력으로 내는 값과 같다(요율을 개정해도 따라간다).
#
# 사용법: scripts/dev-data.sh [계약 건수=3000]     생성 (같은 시드라 매번 같은 데이터)
#         DEV_DATA_TARGET_LR=0.95 scripts/dev-data.sh   목표 손해율을 바꿔 생성 (기본 0.70, 0.10~3.00)
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
DELETE FROM TB_CTR_MILEAGE WHERE CONTRACT_NO LIKE 'GEN-%';
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
TARGET_LR="${DEV_DATA_TARGET_LR:-0.70}"
if ! [[ "$TARGET_LR" =~ ^[0-9]+(\.[0-9]+)?$ ]] || ! awk -v t="$TARGET_LR" 'BEGIN { exit !(t >= 0.10 && t <= 3.00) }'; then
  echo "목표 손해율(DEV_DATA_TARGET_LR)은 0.10~3.00 사이 숫자여야 합니다: $TARGET_LR" >&2
  exit 1
fi
if ! [[ "$COUNT" =~ ^[0-9]+$ ]] || [ "$COUNT" -lt 1 ] || [ "$COUNT" -gt 100000 ]; then
  echo "계약 건수는 1~100000 사이 숫자여야 합니다: $COUNT" >&2
  exit 1
fi
if [ "$("${PSQL[@]}" -tAc "SELECT COUNT(*) FROM TB_CTR_LGD WHERE CONTRACT_NO LIKE 'GEN-%'")" != "0" ]; then
  echo "이미 생성한 데이터가 있습니다. 먼저 scripts/dev-data.sh --cleanup 으로 지워 주세요." >&2
  exit 1
fi

"${PSQL[@]}" -v count="$COUNT" -v target_lr="$TARGET_LR" <<'SQL'
BEGIN;
\o /dev/null
SELECT setseed(0.42);
\o

-- 요율 계수 하나를 보험시기 기준 계수표에서 찾는다. 입력이 없거나 행이 없으면 1 (산출 API 와 같은 규칙: 판매채널은 행이 없으면 1)
CREATE FUNCTION pg_temp.factor(cls text, ftype text, fkey text, num int, on_date date) RETURNS numeric AS $$
  SELECT COALESCE((SELECT FACTOR_VALUE FROM TB_PRM_FACTOR
                    WHERE INSR_CLS_CD = cls AND FACTOR_TYPE = ftype
                      AND EFF_FROM <= on_date AND (EFF_TO IS NULL OR EFF_TO >= on_date)
                      AND (fkey IS NOT NULL AND FACTOR_KEY = fkey OR num IS NOT NULL AND num BETWEEN RANGE_FROM AND RANGE_TO)
                    LIMIT 1), 1)
$$ LANGUAGE sql STABLE;

-- 계약: 2025-01-01 ~ 오늘 사이 시작. 상품은 자동차 표준형 50%, 실속형 20%, 주택화재 12%, 운전자 10%, 해외여행 8%.
-- (2025년 시작 자동차 표준형은 판매가 끝난 2025형 상품으로 둔다)
-- 판매채널: 다이렉트(채널계 ch-direct)가 화재 10%, 그 밖 25%. 나머지는 설계사(agent01/agent02) 대면.
-- 해외여행은 3~30일 여행, 지역 아시아 55%·유럽 20%·미주 15%·그 밖 10%, 나이는 대부분 24~56세에 어린이(8세)·60대(62세)가 섞인다.
CREATE TEMP TABLE G_CTR ON COMMIT DROP AS
SELECT n,
       'GEN-POL-' || LPAD(n::text, 8, '0') AS contract_no,
       'GEN-APP-' || LPAD(n::text, 8, '0') AS appl_no,
       start_date,
       NULL::date AS end_date,
       CASE WHEN p < 0.50 THEN CASE WHEN start_date < DATE '2026-01-01' THEN 'CAR-STD-2025' ELSE 'CAR-STD-2026' END
            WHEN p < 0.70 THEN 'CAR-ECO-2026'
            WHEN p < 0.82 THEN 'FIRE-HOME-2026'
            WHEN p < 0.92 THEN 'DRV-STD-2026' ELSE 'TRV-STD-2026' END AS prd_cd,
       NULL::text AS insr_cls,
       r_ch, r_agent,
       NULL::text AS chnl,
       NULL::text AS reg_emp_id,
       r_term,
       3 + FLOOR(r_days * 28)::int AS travel_days,
       CASE WHEN r_region < 0.55 THEN 'ASIA' WHEN r_region < 0.75 THEN 'EUROPE' WHEN r_region < 0.90 THEN 'AMERICA' ELSE 'ETC' END AS region,
       CASE WHEN r_age < 0.10 THEN 8 WHEN r_age < 0.90 THEN 20 + (r_age * 40)::int ELSE 62 END AS insured_age,
       NULL::numeric AS factor
  FROM (SELECT n, random() AS p, random() AS r_ch, random() AS r_agent, random() AS r_term, random() AS r_days,
               random() AS r_region, random() AS r_age,
               (DATE '2025-01-01' + (random() * (CURRENT_DATE - DATE '2025-01-01'))::int) AS start_date
          FROM generate_series(1, :count) AS n) s;

-- 실속형은 2026년 상품이라, 2025년 시작분은 표준형 2025로 바꾼다
UPDATE G_CTR SET prd_cd = 'CAR-STD-2025' WHERE prd_cd = 'CAR-ECO-2026' AND start_date < DATE '2026-01-01';
-- 주택화재·운전자·해외여행도 2026년 상품이라, 2025년 시작분은 시작일을 2026년으로 옮긴다
UPDATE G_CTR SET start_date = DATE '2026-01-01' + (n % GREATEST(CURRENT_DATE - DATE '2026-01-01', 1))
 WHERE prd_cd IN ('FIRE-HOME-2026', 'DRV-STD-2026', 'TRV-STD-2026') AND start_date < DATE '2026-01-01';

UPDATE G_CTR SET
    insr_cls = CASE WHEN prd_cd LIKE 'CAR-%' THEN '01' WHEN prd_cd LIKE 'FIRE-%' THEN '02' WHEN prd_cd LIKE 'DRV-%' THEN '03' ELSE '04' END,
    end_date = CASE WHEN prd_cd LIKE 'TRV-%' THEN start_date + travel_days ELSE (start_date + INTERVAL '1 year')::date END,
    chnl = CASE WHEN r_ch < CASE WHEN prd_cd LIKE 'FIRE-%' THEN 0.10 ELSE 0.25 END THEN '20' ELSE '10' END;
UPDATE G_CTR SET reg_emp_id = CASE WHEN chnl = '20' THEN 'ch-direct' WHEN r_agent < 0.5 THEN 'agent01' ELSE 'agent02' END;

-- 계약별 요율 계수의 곱 (계약에 저장하는 입력값과 같은 값: 자동차 35세·3등급, 화재 2급, 운전자 40세, 해외여행 지역·나이, 판매채널)
UPDATE G_CTR SET factor =
      CASE insr_cls
        WHEN '01' THEN pg_temp.factor(insr_cls, 'DRIVER_AGE', NULL, 35, start_date) * pg_temp.factor(insr_cls, 'DISCOUNT_GRADE', '3', NULL, start_date)
        WHEN '02' THEN pg_temp.factor(insr_cls, 'STRUCTURE_GRADE', '2', NULL, start_date)
        WHEN '03' THEN pg_temp.factor(insr_cls, 'DRIVER_AGE', NULL, 40, start_date)
        ELSE pg_temp.factor(insr_cls, 'TRAVEL_REGION', region, NULL, start_date) * pg_temp.factor(insr_cls, 'INSURED_AGE', NULL, insured_age, start_date)
      END
    * pg_temp.factor(insr_cls, 'SALES_CHANNEL', chnl, NULL, start_date);

-- 담보: 상품 구성대로 (필수 담보는 항상, 선택 특약은 확률로), 가입금액은 상품 한도 안으로 맞춘다.
-- 보험료 = 가입금액 × 보험시기 기준 연 요율 × 계수 × 보험기간 일수 ÷ 365 (원 단위 반올림, 산출 API 와 같은 식)
CREATE TEMP TABLE G_CVRG ON COMMIT DROP AS
SELECT c.contract_no, pc.CVRG_CD AS cvrg_cd,
       GREATEST(pc.MIN_ISRD_AMT, LEAST(pc.MAX_ISRD_AMT, x.isrd_amt)) AS isrd_amt,
       ROUND(GREATEST(pc.MIN_ISRD_AMT, LEAST(pc.MAX_ISRD_AMT, x.isrd_amt)) * r.ANNUAL_RATE * c.factor
             * (c.end_date - c.start_date) / 365.0) AS prem
  FROM G_CTR c
  JOIN TB_PRD_CVRG pc ON pc.PRD_CD = c.prd_cd
  JOIN TB_PRM_RATE r ON r.INSR_CLS_CD = c.insr_cls AND r.CVRG_CD = pc.CVRG_CD
                    AND r.EFF_FROM <= c.start_date AND (r.EFF_TO IS NULL OR r.EFF_TO >= c.start_date)
  -- random() 을 쓰는 값은 바깥 행(c.n)을 참조해야 계약마다 새로 뽑힌다 (참조가 없으면 한 번만 계산될 수 있다)
  CROSS JOIN LATERAL (VALUES
      ('CA001', 100000000::numeric, 1.0), ('CA002', (2 + FLOOR(random() * 9 + 0 * c.n)) * 10000000, 1.0),
      ('CA003', 30000000, 0.5), ('CA004', (1 + FLOOR(random() * 5 + 0 * c.n)) * 10000000, 0.6),
      ('FI001', (1 + FLOOR(random() * 10 + 0 * c.n)) * 100000000, 1.0), ('FI002', (1 + FLOOR(random() * 10 + 0 * c.n)) * 10000000, 0.6),
      ('FI003', 50000000, 0.4),
      ('DR001', 100000000, 1.0), ('DR002', 20000000, 0.6), ('DR003', 30000000, 0.5), ('DR004', 50000000, 0.4),
      ('TR001', 50000000, 1.0), ('TR002', 50000000, 1.0), ('TR003', 1000000, 0.5), ('TR004', 30000000, 0.3), ('TR005', 300000, 0.4)
    ) AS x(cvrg_cd, isrd_amt, p_take)
 WHERE x.cvrg_cd = pc.CVRG_CD AND (pc.REQUIRED_YN OR random() < x.p_take);

-- 계약 원장: 해외여행을 뺀 계약의 10% 는 해지(해지일은 시작~min(종기, 오늘) 사이), 나머지는 유효. 모두 완납 후 승인된 것으로 둔다
INSERT INTO TB_CTR_LGD (CONTRACT_NO, APPL_NO, INSR_CLS_CD, PRD_CD, CTR_STAT_CD, START_DATE, END_DATE, TERM_DT, REFUND_PREM,
                        POLICYHOLDER_ID, INSURED_ID, TOTAL_PREMIUM, SYS_REG_DT, REG_EMP_ID, DRIVER_AGE, DISCOUNT_GRADE, STRUCTURE_GRADE,
                        TRAVEL_REGION, INSURED_AGE, SALES_CHNL_CD, GLOBAL_TX_ID)
SELECT c.contract_no, c.appl_no, c.insr_cls, c.prd_cd,
       CASE WHEN c.r_term < 0.10 AND c.insr_cls <> '04' THEN '30' ELSE '20' END,
       c.start_date, c.end_date,
       CASE WHEN c.r_term < 0.10 AND c.insr_cls <> '04'
            THEN c.start_date + 1 + (c.r_term * 10 * GREATEST(LEAST(c.end_date, CURRENT_DATE) - c.start_date - 1, 0))::int END,
       NULL,
       'GEN-C' || LPAD(c.n::text, 6, '0'), 'GEN-C' || LPAD(c.n::text, 6, '0'),
       (SELECT COALESCE(SUM(v.prem), 0) FROM G_CVRG v WHERE v.contract_no = c.contract_no),
       (c.start_date - CASE WHEN c.insr_cls = '04' THEN 3 ELSE 7 END)::timestamp + TIME '10:00', c.reg_emp_id,
       CASE c.insr_cls WHEN '01' THEN 35 WHEN '03' THEN 40 END, CASE WHEN c.insr_cls = '01' THEN '3' END,
       CASE WHEN c.insr_cls = '02' THEN '2' END,
       CASE WHEN c.insr_cls = '04' THEN c.region END, CASE WHEN c.insr_cls = '04' THEN c.insured_age END,
       c.chnl,
       'gen-apply-' || c.n
  FROM G_CTR c;

-- 해지 환급보험료 (총보험료 × 남은 일수 ÷ 보험기간 일수)
UPDATE TB_CTR_LGD SET REFUND_PREM = ROUND(TOTAL_PREMIUM * GREATEST(END_DATE - TERM_DT, 0) / (END_DATE - START_DATE))
 WHERE CONTRACT_NO LIKE 'GEN-%' AND CTR_STAT_CD = '30';

INSERT INTO TB_CTR_CVRG (CONTRACT_NO, CVRG_CD, ISRD_AMT, PREM) SELECT contract_no, cvrg_cd, isrd_amt, prem FROM G_CVRG;

-- 납입(완납 1회)과 상태 이력(접수 → 승인 → 해지)
INSERT INTO TB_CTR_PAYMENT (CONTRACT_NO, PAY_AMT, PAY_METHOD, EMP_ID, GLOBAL_TX_ID, PAID_DT)
SELECT CONTRACT_NO, TOTAL_PREMIUM, CASE WHEN SALES_CHNL_CD = '20' THEN 'CARD' ELSE 'TRANSFER' END,
       CASE WHEN SALES_CHNL_CD = '20' THEN 'ch-direct' ELSE 'op01' END, 'gen-pay-' || CONTRACT_NO, SYS_REG_DT + INTERVAL '1 day'
  FROM TB_CTR_LGD WHERE CONTRACT_NO LIKE 'GEN-%' AND TOTAL_PREMIUM > 0;
INSERT INTO TB_CTR_STAT_HIST (CONTRACT_NO, BEFORE_STAT_CD, AFTER_STAT_CD, EMP_ID, GLOBAL_TX_ID, CHG_DT)
SELECT CONTRACT_NO, NULL, '10', REG_EMP_ID, 'gen-apply-h-' || CONTRACT_NO, SYS_REG_DT FROM TB_CTR_LGD WHERE CONTRACT_NO LIKE 'GEN-%'
UNION ALL
SELECT CONTRACT_NO, '10', '20', 'uw01', 'gen-approve-' || CONTRACT_NO, SYS_REG_DT + INTERVAL '2 day' FROM TB_CTR_LGD WHERE CONTRACT_NO LIKE 'GEN-%'
UNION ALL
SELECT CONTRACT_NO, '20', '30', 'op01', 'gen-term-' || CONTRACT_NO, TERM_DT + TIME '15:00' FROM TB_CTR_LGD
 WHERE CONTRACT_NO LIKE 'GEN-%' AND CTR_STAT_CD = '30';

-- 사고: 담보마다 0~1건. 1년 상품은 연간 사고 빈도 × 보장한 일수 ÷ 365,
-- 해외여행은 여행 한 번당 사고 빈도 × 보장한 일수 ÷ 여행 일수 (아직 끝나지 않은 여행은 지난 날만큼만). 사고일은 보장 기간 안, 오늘 이전.
-- 손해액은 그 담보의 보험료에서 역산한다: 평균 손해액 = 목표 손해율 × 담보 보험료 ÷ 빈도, 실제 값은 평균의 0.2~1.8배.
-- 그러면 담보마다 기대 손해율이 목표에 가깝다(자기부담금·가입금액 한도·면책 때문에 실제로는 조금 낮다).
-- 빈도와 손해액을 따로 정하면 예시 요율과 맞지 않아 손해율이 수백 %나 수 % 로 튀어, 요율 영향 시뮬레이션이 의미 없는 값을 낸다.
CREATE TEMP TABLE G_CLM ON COMMIT DROP AS
SELECT ROW_NUMBER() OVER (ORDER BY l.CONTRACT_NO, v.CVRG_CD) AS k, l.CONTRACT_NO, l.INSURED_ID, v.CVRG_CD, v.ISRD_AMT,
       l.SALES_CHNL_CD AS chnl,
       acdt, GREATEST(ROUND(:target_lr * v.PREM / f.freq * (0.2 + 1.6 * random()) / 1000) * 1000, 1000) AS loss,
       COALESCE(pc.DEDUCTIBLE_AMT, 0) AS deductible, random() AS r_stat
  FROM TB_CTR_LGD l
  JOIN TB_CTR_CVRG v ON v.CONTRACT_NO = l.CONTRACT_NO
  -- 빈도: 1년 상품은 연간, 해외여행은 여행 한 번당 (임의값)
  JOIN (VALUES ('CA001', 0.02), ('CA002', 0.08), ('CA003', 0.04), ('CA004', 0.12),
               ('FI001', 0.005), ('FI002', 0.02), ('FI003', 0.01),
               ('DR001', 0.01), ('DR002', 0.01), ('DR003', 0.01), ('DR004', 0.003),
               ('TR001', 0.02), ('TR002', 0.04), ('TR003', 0.03), ('TR004', 0.003), ('TR005', 0.05)) AS f(cvrg_cd, freq)
    ON f.cvrg_cd = v.CVRG_CD
  LEFT JOIN TB_PRD_CVRG pc ON pc.PRD_CD = l.PRD_CD AND pc.CVRG_CD = v.CVRG_CD
  CROSS JOIN LATERAL (SELECT LEAST(l.END_DATE, COALESCE(l.TERM_DT, l.END_DATE), CURRENT_DATE + 1) - l.START_DATE AS days) d
  CROSS JOIN LATERAL (SELECT l.START_DATE + (random() * GREATEST(d.days - 1, 0))::int AS acdt) a
 WHERE l.CONTRACT_NO LIKE 'GEN-%' AND d.days > 0
   AND random() < f.freq * d.days / CASE WHEN l.INSR_CLS_CD = '04' THEN (l.END_DATE - l.START_DATE)::numeric ELSE 365.0 END;

-- 상태: 60일 넘은 사고는 지급 85% · 면책 10% · 지급 대기 5%, 최근 사고는 접수 40% · 지급 대기 30% · 지급 30%
-- 지급액 = min(손해액 - 자기부담금, 가입금액). 같은 담보에 사고가 한 건이라 기지급 한도는 따로 보지 않는다
INSERT INTO TB_CLM (CLM_NO, CONTRACT_NO, CLM_STAT_CD, ACDT_DT, ACDT_DESC, CLAIMANT_ID, TOTAL_CLAIM_AMT, TOTAL_PAY_AMT, RPT_DT, RPT_EMP_ID,
                    ADJ_EMP_ID, ADJ_DT, DECIDE_EMP_ID, DECIDE_DT, DECIDE_MEMO, GLOBAL_TX_ID, REQ_HASH)
SELECT 'GEN-CLM-' || LPAD(k::text, 8, '0'), CONTRACT_NO, stat, acdt, '생성 데이터 사고 (' || CVRG_CD || ')', INSURED_ID,
       GREATEST(loss, 1000),
       CASE WHEN stat IN ('20', '40') THEN pay WHEN stat = '80' THEN 0 END,
       (acdt + 1)::timestamp + TIME '09:30', CASE WHEN chnl = '20' THEN 'ch-direct' ELSE 'agent01' END,
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

-- 마일리지 특약: 자동차 계약의 40% 가 보험시기 3일 뒤 시작 주행거리를 등록. 정산 기간(종기 30일 전)이 된 유효 계약은
-- 정산까지 해 둔다. 주행거리는 2,000~18,000km, 환급률은 1년 환산 주행거리로 환급률표(보험시기 기준)에서 찾고 원 미만 버림
INSERT INTO TB_CTR_MILEAGE (CONTRACT_NO, MIL_STAT_CD, START_ODO_KM, START_REG_DT, START_EMP_ID, START_TX_ID)
SELECT CONTRACT_NO, '10', odo, (START_DATE + 3)::timestamp + TIME '11:00',
       CASE WHEN SALES_CHNL_CD = '20' THEN 'ch-direct' ELSE 'op01' END, 'gen-mil-s-' || CONTRACT_NO
  FROM (SELECT l.*, 10000 + (random() * 90000)::int AS odo, random() AS r
          FROM TB_CTR_LGD l WHERE l.CONTRACT_NO LIKE 'GEN-%' AND l.INSR_CLS_CD = '01' AND l.START_DATE + 3 <= CURRENT_DATE) x
 WHERE r < 0.40;

UPDATE TB_CTR_MILEAGE m SET
       MIL_STAT_CD = '20', END_ODO_KM = m.START_ODO_KM + s.driven, END_REG_DT = s.settled_at,
       END_EMP_ID = CASE WHEN l.SALES_CHNL_CD = '20' THEN 'ch-direct' ELSE 'op01' END, END_TX_ID = 'gen-mil-e-' || m.CONTRACT_NO,
       DRIVEN_KM = s.driven, ANNUAL_KM = s.annual,
       REFUND_RATE = COALESCE(s.rate, 0), REFUND_BASE_PREM = l.TOTAL_PREMIUM,
       REFUND_AMT = FLOOR(l.TOTAL_PREMIUM * COALESCE(s.rate, 0))
  FROM TB_CTR_LGD l
  CROSS JOIN LATERAL (SELECT 2000 + (random() * 16000 + 0 * (l.END_DATE - l.START_DATE))::int AS driven) dv
  CROSS JOIN LATERAL (SELECT dv.driven,
                             ROUND(dv.driven * 365.0 / (l.END_DATE - l.START_DATE))::int AS annual,
                             LEAST(CURRENT_DATE, l.END_DATE + 5)::timestamp + TIME '15:00' AS settled_at) a
  CROSS JOIN LATERAL (SELECT a.driven, a.annual, a.settled_at,
                             (SELECT REFUND_RATE FROM TB_PRM_MILEAGE_RATE r
                               WHERE a.annual BETWEEN r.KM_FROM AND r.KM_TO
                                 AND r.EFF_FROM <= l.START_DATE AND (r.EFF_TO IS NULL OR r.EFF_TO >= l.START_DATE)) AS rate) s
 WHERE l.CONTRACT_NO = m.CONTRACT_NO AND m.CONTRACT_NO LIKE 'GEN-%'
   AND l.CTR_STAT_CD = '20' AND l.END_DATE - 30 <= CURRENT_DATE;

COMMIT;
SQL

"${PSQL[@]}" -tAc "SELECT '계약 ' || COUNT(*) || '건 (해지 ' || COUNT(*) FILTER (WHERE CTR_STAT_CD = '30') || '건, 다이렉트 '
  || COUNT(*) FILTER (WHERE SALES_CHNL_CD = '20') || '건), 사고 '
  || (SELECT COUNT(*) FROM TB_CLM WHERE CLM_NO LIKE 'GEN-%') || '건, 마일리지 '
  || (SELECT COUNT(*) FROM TB_CTR_MILEAGE WHERE CONTRACT_NO LIKE 'GEN-%') || '건(정산 '
  || (SELECT COUNT(*) FROM TB_CTR_MILEAGE WHERE CONTRACT_NO LIKE 'GEN-%' AND MIL_STAT_CD = '20') || '건)을 만들었습니다.'
  FROM TB_CTR_LGD WHERE CONTRACT_NO LIKE 'GEN-%'"
"${PSQL[@]}" -tAc "SELECT '  보험종목 ' || INSR_CLS_CD || ': 계약 ' || COUNT(*) || '건' FROM TB_CTR_LGD WHERE CONTRACT_NO LIKE 'GEN-%' GROUP BY INSR_CLS_CD ORDER BY 1"
echo "지울 때: scripts/dev-data.sh --cleanup"

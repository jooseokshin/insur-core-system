-- 보험종목 03 운전자보험, 04 해외여행자보험을 더한다. 요율·계수·자기부담금은 모두 개발용 예시 값이며 실제 요율이 아니다.
-- 운전자보험은 자동차보험이 보상하지 않는 운전자 본인의 형사·행정 비용과 상해를 1년 단위로 보장하고,
-- 해외여행자보험은 여행 기간(1~90일)만 보장하는 단기 상품이다. 보험료는 다른 종목과 같은 식(가입금액 × 연 요율 × 계수 × 일수 ÷ 365)이다.

-- 상품별 보험기간 범위 ----------------------------------------------------------------------------------
ALTER TABLE TB_PRD ADD COLUMN MIN_TERM_DAYS INTEGER;
ALTER TABLE TB_PRD ADD COLUMN MAX_TERM_DAYS INTEGER;
ALTER TABLE TB_PRD ADD CONSTRAINT CK_PRD_TERM_DAYS CHECK (
    (MIN_TERM_DAYS IS NULL OR MIN_TERM_DAYS >= 1)
    AND (MAX_TERM_DAYS IS NULL OR MAX_TERM_DAYS >= 1)
    AND (MIN_TERM_DAYS IS NULL OR MAX_TERM_DAYS IS NULL OR MIN_TERM_DAYS <= MAX_TERM_DAYS));
COMMENT ON COLUMN TB_PRD.MIN_TERM_DAYS IS '보험기간 최소 일수 (포함). null 이면 제한 없음. 벗어나면 산출·청약이 E-PRD-007';
COMMENT ON COLUMN TB_PRD.MAX_TERM_DAYS IS '보험기간 최대 일수 (포함). null 이면 제한 없음';

-- 해외여행 요율 계수 입력을 계약에 남긴다 (자동차·화재 계수와 같은 방식) ------------------------------------------
ALTER TABLE TB_CTR_LGD ADD COLUMN TRAVEL_REGION VARCHAR(10);
ALTER TABLE TB_CTR_LGD ADD COLUMN INSURED_AGE INTEGER;
COMMENT ON COLUMN TB_CTR_LGD.TRAVEL_REGION IS '보험료 산출에 쓴 여행 지역 (해외여행: ASIA, EUROPE, AMERICA, ETC, 입력하지 않았으면 null)';
COMMENT ON COLUMN TB_CTR_LGD.INSURED_AGE IS '보험료 산출에 쓴 피보험자 만 나이 (해외여행, 입력하지 않았으면 null)';

-- 담보 기본 요율 -------------------------------------------------------------------------------------
INSERT INTO TB_PRM_RATE (INSR_CLS_CD, CVRG_CD, CVRG_NM, ANNUAL_RATE, EFF_FROM) VALUES
    ('03', 'DR001', '교통사고처리지원금', 0.000600, DATE '2000-01-01'),
    ('03', 'DR002', '변호사선임비용',     0.002000, DATE '2000-01-01'),
    ('03', 'DR003', '벌금',               0.001200, DATE '2000-01-01'),
    ('03', 'DR004', '운전자상해(사망)',   0.000800, DATE '2000-01-01'),
    ('04', 'TR001', '해외상해의료비',     0.004000, DATE '2000-01-01'),
    ('04', 'TR002', '해외질병의료비',     0.006000, DATE '2000-01-01'),
    ('04', 'TR003', '휴대품손해',         0.050000, DATE '2000-01-01'),
    ('04', 'TR004', '해외배상책임',       0.001000, DATE '2000-01-01'),
    ('04', 'TR005', '항공기·수하물지연',  0.080000, DATE '2000-01-01');

-- 요율 계수 ------------------------------------------------------------------------------------------
INSERT INTO TB_PRM_FACTOR (INSR_CLS_CD, FACTOR_TYPE, FACTOR_KEY, RANGE_FROM, RANGE_TO, FACTOR_NM, FACTOR_VALUE, EFF_FROM) VALUES
    -- 운전자: 운전자 연령 구간
    ('03', 'DRIVER_AGE', NULL, 18, 25, '만 18~25세', 1.3000, DATE '2000-01-01'),
    ('03', 'DRIVER_AGE', NULL, 26, 35, '만 26~35세', 1.1000, DATE '2000-01-01'),
    ('03', 'DRIVER_AGE', NULL, 36, 60, '만 36~60세', 1.0000, DATE '2000-01-01'),
    ('03', 'DRIVER_AGE', NULL, 61, 150, '만 61세 이상', 1.2000, DATE '2000-01-01'),
    -- 해외여행: 여행 지역 (의료비 수준 차이를 흉내 낸 값)
    ('04', 'TRAVEL_REGION', 'ASIA',    NULL, NULL, '아시아',   1.0000, DATE '2000-01-01'),
    ('04', 'TRAVEL_REGION', 'EUROPE',  NULL, NULL, '유럽',     1.3000, DATE '2000-01-01'),
    ('04', 'TRAVEL_REGION', 'AMERICA', NULL, NULL, '미주',     1.6000, DATE '2000-01-01'),
    ('04', 'TRAVEL_REGION', 'ETC',     NULL, NULL, '그 밖',    1.2000, DATE '2000-01-01'),
    -- 해외여행: 피보험자 나이 (80세 이상은 구간이 없어 가입할 수 없다 = E-PRM-004)
    ('04', 'INSURED_AGE', NULL, 0, 14,  '만 0~14세',  0.8000, DATE '2000-01-01'),
    ('04', 'INSURED_AGE', NULL, 15, 59, '만 15~59세', 1.0000, DATE '2000-01-01'),
    ('04', 'INSURED_AGE', NULL, 60, 69, '만 60~69세', 1.5000, DATE '2000-01-01'),
    ('04', 'INSURED_AGE', NULL, 70, 79, '만 70~79세', 2.0000, DATE '2000-01-01'),
    -- 판매채널 (V23 과 같은 방식, 대면은 행이 없어 1.0)
    ('03', 'SALES_CHANNEL', '20', NULL, NULL, '다이렉트 채널', 0.9000, DATE '2000-01-01'),
    ('04', 'SALES_CHANNEL', '20', NULL, NULL, '다이렉트 채널', 0.9000, DATE '2000-01-01');

-- 필수 요율 계수 ---------------------------------------------------------------------------------------
INSERT INTO TB_PRM_FACTOR_POLICY (INSR_CLS_CD, FACTOR_TYPE, REQUIRED_YN, EFF_FROM) VALUES
    ('03', 'DRIVER_AGE', TRUE, DATE '2000-01-01'),
    ('04', 'TRAVEL_REGION', TRUE, DATE '2000-01-01'),
    ('04', 'INSURED_AGE', TRUE, DATE '2000-01-01');

-- 상품과 담보 구성 -------------------------------------------------------------------------------------
INSERT INTO TB_PRD (PRD_CD, PRD_NM, INSR_CLS_CD, PRD_DESC, SALE_FROM, SALE_TO, MIN_TERM_DAYS, MAX_TERM_DAYS) VALUES
    ('DRV-STD-2026', '운전자보험 표준형', '03',
     '교통사고처리지원금을 기본으로 변호사선임비용·벌금·운전자상해를 특약으로 더할 수 있는 1년 상품', DATE '2026-01-01', NULL, 365, 366),
    ('TRV-STD-2026', '해외여행자보험 표준형', '04',
     '해외 상해·질병 의료비를 기본으로 휴대품·배상책임·지연 비용을 특약으로 더할 수 있는 1~90일 상품', DATE '2026-01-01', NULL, 1, 90);

INSERT INTO TB_PRD_CVRG (PRD_CD, CVRG_CD, CVRG_TYPE, REQUIRED_YN, MIN_ISRD_AMT, MAX_ISRD_AMT, DEDUCTIBLE_AMT, SORT_ORD) VALUES
    ('DRV-STD-2026', 'DR001', 'MAIN',  TRUE,  20000000, 200000000,      0, 1),
    ('DRV-STD-2026', 'DR002', 'RIDER', FALSE,  5000000,  30000000,      0, 2),
    ('DRV-STD-2026', 'DR003', 'RIDER', FALSE, 20000000,  30000000,      0, 3),
    ('DRV-STD-2026', 'DR004', 'RIDER', FALSE, 10000000, 100000000,      0, 4),
    ('TRV-STD-2026', 'TR001', 'MAIN',  TRUE,  10000000, 100000000,      0, 1),
    ('TRV-STD-2026', 'TR002', 'RIDER', TRUE,  10000000, 100000000,      0, 2),
    ('TRV-STD-2026', 'TR003', 'RIDER', FALSE,   200000,   2000000,  10000, 3),
    ('TRV-STD-2026', 'TR004', 'RIDER', FALSE, 10000000,  50000000,      0, 4),
    ('TRV-STD-2026', 'TR005', 'RIDER', FALSE,   100000,    500000,      0, 5);

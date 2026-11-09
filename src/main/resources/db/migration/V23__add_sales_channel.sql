-- 판매채널. 계약이 대면(설계사·직원 화면), 다이렉트(홈페이지·앱 채널계), TM(텔레마케팅 채널계) 중 어디로 들어왔는지 남긴다.
-- 채널은 요청 바디가 아니라 토큰으로 정한다: 직원 토큰은 10, 채널 시스템 토큰(OAuth client credentials)은 인증 서버에 등록한 채널.
-- 기존 계약은 모두 업무 화면으로 들어왔으므로 10(대면)으로 채운다.
ALTER TABLE TB_CTR_LGD ADD COLUMN SALES_CHNL_CD VARCHAR(2) NOT NULL DEFAULT '10';
ALTER TABLE TB_CTR_LGD ADD CONSTRAINT CK_CTR_LGD_SALES_CHNL CHECK (SALES_CHNL_CD IN ('10', '20', '30'));
COMMENT ON COLUMN TB_CTR_LGD.SALES_CHNL_CD IS '판매채널코드 (10:대면, 20:다이렉트, 30:TM). 갱신 계약은 원 계약의 채널을 잇는다';

-- 채널별 요율 계수 (키 = 판매채널코드). 서버가 채널로 찾아 곱하고, 행이 없는 채널(대면)은 1.0 이다.
-- 다이렉트는 설계사 수수료 같은 판매 비용이 적어 보험료가 낮다는 구조를 흉내 낸 개발용 예시 값이며 실제 요율이 아니다.
-- 바꿀 때는 요율 계수 관리(factor-revise, ADMIN)로 적용 시작일을 정해 개정한다.
INSERT INTO TB_PRM_FACTOR (INSR_CLS_CD, FACTOR_TYPE, FACTOR_KEY, RANGE_FROM, RANGE_TO, FACTOR_NM, FACTOR_VALUE, EFF_FROM) VALUES
    ('01', 'SALES_CHANNEL', '20', NULL, NULL, '다이렉트 채널', 0.8500, DATE '2000-01-01'),
    ('01', 'SALES_CHANNEL', '30', NULL, NULL, 'TM 채널', 0.9200, DATE '2000-01-01'),
    ('02', 'SALES_CHANNEL', '20', NULL, NULL, '다이렉트 채널', 0.9000, DATE '2000-01-01');

-- 멱등성 강화: 청약 요청 내용의 지문(SHA-256 16진수 64자)을 보관해, 같은 거래 UUID 의 재전송이 처음과 같은 내용인지 비교한다.
-- 이 컬럼이 생기기 전에 접수한 계약은 NULL 이며, NULL 인 계약은 내용 비교를 하지 않는다.
ALTER TABLE TB_CTR_LGD ADD COLUMN REQ_HASH VARCHAR(64);

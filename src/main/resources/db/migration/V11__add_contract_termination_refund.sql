-- 해지 환급보험료: 해지(상태 30)로 바꿀 때 해지일과 계산한 환급보험료를 계약에 남긴다. 해지하지 않은 계약은 NULL.
ALTER TABLE TB_CTR_LGD ADD COLUMN TERM_DT     DATE;
ALTER TABLE TB_CTR_LGD ADD COLUMN REFUND_PREM NUMERIC(15, 0);

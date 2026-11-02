-- 업무 화면 조회용 인덱스
-- 만기·갱신 대상: 유효(20) 계약을 보험종기 범위로 찾는다
CREATE INDEX IX_CTR_LGD_STAT_END_DATE ON TB_CTR_LGD (CTR_STAT_CD, END_DATE);

-- 업무 현황의 오늘 승인·취소·해지 건수: 변경 일시 범위로 찾는다
CREATE INDEX IX_CTR_STAT_HIST_CHG_DT ON TB_CTR_STAT_HIST (CHG_DT);

-- 업무 현황의 오늘 수납 실적: 납입 일시 범위로 찾는다
CREATE INDEX IX_CTR_PAYMENT_PAID_DT ON TB_CTR_PAYMENT (PAID_DT);

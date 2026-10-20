-- 데이터 단위 권한: 청약을 접수한 사번을 계약 원장에 보관한다. (AGENT 는 본인이 접수한 계약만 조회)
-- V3 이후 접수한 계약은 상태 이력의 최초 기록(BEFORE_STAT_CD 가 NULL)에서 접수 사번을 채운다. 그 이전 계약은 NULL 로 남는다.
ALTER TABLE TB_CTR_LGD ADD COLUMN REG_EMP_ID VARCHAR(64);

UPDATE TB_CTR_LGD lgd
   SET REG_EMP_ID = hist.EMP_ID
  FROM TB_CTR_STAT_HIST hist
 WHERE hist.CONTRACT_NO = lgd.CONTRACT_NO
   AND hist.BEFORE_STAT_CD IS NULL;

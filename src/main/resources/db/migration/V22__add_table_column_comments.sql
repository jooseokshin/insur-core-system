-- 모든 업무 테이블과 컬럼에 설명(COMMENT)을 단다. DB 도구(DBeaver 등)에서 바로 뜻을 볼 수 있게 하려는 것이고, 동작은 바꾸지 않는다.
-- 앞으로 테이블이나 컬럼을 추가하는 마이그레이션은 같은 파일에 COMMENT 도 함께 쓴다 (SchemaCommentTest 가 빠진 것을 잡는다).
-- 코드값은 애플리케이션 상수와 같다. 금액은 원 단위 정수, 일시는 업무 시간대(Asia/Seoul) 기준이다.

-- 계약 ---------------------------------------------------------------------------------------------
COMMENT ON TABLE TB_CTR_LGD IS '계약 원장. 청약 접수부터 유효·해지·청약취소까지 계약 한 건의 현재 상태';
COMMENT ON COLUMN TB_CTR_LGD.CONTRACT_NO IS '증권번호 (POL-{상품구분}-{연도}-{6자리 일련번호})';
COMMENT ON COLUMN TB_CTR_LGD.APPL_NO IS '청약번호 (APP-{상품구분}-{연도}-{6자리 일련번호})';
COMMENT ON COLUMN TB_CTR_LGD.INSR_CLS_CD IS '보험종목코드 (01:자동차, 02:화재)';
COMMENT ON COLUMN TB_CTR_LGD.CTR_STAT_CD IS '계약상태코드 (10:청약, 20:유효, 30:해지, 90:청약취소)';
COMMENT ON COLUMN TB_CTR_LGD.START_DATE IS '보험시기 (보장 시작일, 포함)';
COMMENT ON COLUMN TB_CTR_LGD.END_DATE IS '보험종기 (보장 종료일, 이 날은 보장하지 않음)';
COMMENT ON COLUMN TB_CTR_LGD.POLICYHOLDER_ID IS '계약자 ID (배서로 바뀔 수 있음)';
COMMENT ON COLUMN TB_CTR_LGD.INSURED_ID IS '피보험자 ID (배서로 바뀔 수 있음)';
COMMENT ON COLUMN TB_CTR_LGD.TOTAL_PREMIUM IS '총보험료 (담보 보험료 합계, 원)';
COMMENT ON COLUMN TB_CTR_LGD.SYS_REG_DT IS '시스템 등록일시 (청약 접수 시각)';
COMMENT ON COLUMN TB_CTR_LGD.GLOBAL_TX_ID IS '청약 접수 거래 UUID (멱등성 키). 마이그레이션 이전 계약은 null';
COMMENT ON COLUMN TB_CTR_LGD.REG_EMP_ID IS '청약 접수 사번 (토큰 sub). 설계사(AGENT)는 이 값이 본인인 계약만 조회한다';
COMMENT ON COLUMN TB_CTR_LGD.REQ_HASH IS '청약 요청 내용 지문 (SHA-256 16진수). 같은 거래 UUID 재전송이 같은 내용인지 비교한다';
COMMENT ON COLUMN TB_CTR_LGD.DRIVER_AGE IS '보험료 산출에 쓴 운전자 만 나이 (자동차, 입력하지 않았으면 null)';
COMMENT ON COLUMN TB_CTR_LGD.DISCOUNT_GRADE IS '보험료 산출에 쓴 할인할증 등급 (자동차, 입력하지 않았으면 null)';
COMMENT ON COLUMN TB_CTR_LGD.STRUCTURE_GRADE IS '보험료 산출에 쓴 건물 구조 등급 (화재, 입력하지 않았으면 null)';
COMMENT ON COLUMN TB_CTR_LGD.TERM_DT IS '해지일 (상태를 30으로 바꾼 날). 해지하지 않았으면 null';
COMMENT ON COLUMN TB_CTR_LGD.REFUND_PREM IS '해지 환급보험료 (일할 계산, 원). 해지하지 않았으면 null';
COMMENT ON COLUMN TB_CTR_LGD.RENEWED_FROM IS '갱신 계약이면 원 계약의 증권번호. 갱신 계약이 아니면 null';
COMMENT ON COLUMN TB_CTR_LGD.PRD_CD IS '상품코드 (TB_PRD). 상품 도입 전 계약이나 상품 없이 접수한 청약은 null';

COMMENT ON TABLE TB_CTR_CVRG IS '계약 담보. 계약별로 가입한 담보와 가입금액·담보 보험료';
COMMENT ON COLUMN TB_CTR_CVRG.ID IS '담보 일련번호';
COMMENT ON COLUMN TB_CTR_CVRG.CONTRACT_NO IS '증권번호 (TB_CTR_LGD)';
COMMENT ON COLUMN TB_CTR_CVRG.CVRG_CD IS '담보코드 (예: CA001 대인배상, FI001 건물)';
COMMENT ON COLUMN TB_CTR_CVRG.ISRD_AMT IS '가입금액 (보상 한도, 원)';
COMMENT ON COLUMN TB_CTR_CVRG.PREM IS '담보 보험료 (가입금액 × 연 요율 × 계수 × 보험기간 일수 ÷ 365, 원)';

COMMENT ON TABLE TB_CTR_STAT_HIST IS '계약 상태 변경 이력 (추가만 함)';
COMMENT ON COLUMN TB_CTR_STAT_HIST.HIST_ID IS '이력 일련번호 (발생 순서)';
COMMENT ON COLUMN TB_CTR_STAT_HIST.CONTRACT_NO IS '증권번호 (TB_CTR_LGD)';
COMMENT ON COLUMN TB_CTR_STAT_HIST.BEFORE_STAT_CD IS '변경 전 계약상태코드. 청약 접수(최초 상태)는 null';
COMMENT ON COLUMN TB_CTR_STAT_HIST.AFTER_STAT_CD IS '변경 후 계약상태코드 (10:청약, 20:유효, 30:해지, 90:청약취소)';
COMMENT ON COLUMN TB_CTR_STAT_HIST.EMP_ID IS '처리 사번 (토큰 sub)';
COMMENT ON COLUMN TB_CTR_STAT_HIST.GLOBAL_TX_ID IS '변경을 일으킨 거래 UUID. (계약, 거래 UUID) 단위로 재전송을 판별한다';
COMMENT ON COLUMN TB_CTR_STAT_HIST.CHG_DT IS '변경 일시';

COMMENT ON TABLE TB_CTR_ENDORSE IS '배서 이력. 계약자·피보험자 변경을 바뀐 항목마다 한 행씩 남긴다';
COMMENT ON COLUMN TB_CTR_ENDORSE.ENDORSE_ID IS '배서 항목 일련번호 (발생 순서)';
COMMENT ON COLUMN TB_CTR_ENDORSE.CONTRACT_NO IS '증권번호 (TB_CTR_LGD)';
COMMENT ON COLUMN TB_CTR_ENDORSE.FIELD_NM IS '변경한 항목 (POLICYHOLDER_ID:계약자, INSURED_ID:피보험자)';
COMMENT ON COLUMN TB_CTR_ENDORSE.BEFORE_VAL IS '변경 전 값';
COMMENT ON COLUMN TB_CTR_ENDORSE.AFTER_VAL IS '변경 후 값';
COMMENT ON COLUMN TB_CTR_ENDORSE.EMP_ID IS '처리 사번';
COMMENT ON COLUMN TB_CTR_ENDORSE.GLOBAL_TX_ID IS '변경을 일으킨 거래 UUID';
COMMENT ON COLUMN TB_CTR_ENDORSE.CHG_DT IS '변경 일시';

COMMENT ON TABLE TB_CTR_PAYMENT IS '보험료 납입(수납) 내역. 분납 가능, 납입 합계가 총보험료에 이르러야 승인(20)할 수 있다';
COMMENT ON COLUMN TB_CTR_PAYMENT.PAYMENT_ID IS '납입 일련번호 (발생 순서)';
COMMENT ON COLUMN TB_CTR_PAYMENT.CONTRACT_NO IS '증권번호 (TB_CTR_LGD)';
COMMENT ON COLUMN TB_CTR_PAYMENT.PAY_AMT IS '납입 금액 (원, 양수)';
COMMENT ON COLUMN TB_CTR_PAYMENT.PAY_METHOD IS '납부 방법 (CASH:현금, TRANSFER:계좌이체, CARD:카드)';
COMMENT ON COLUMN TB_CTR_PAYMENT.EMP_ID IS '수납 처리 사번';
COMMENT ON COLUMN TB_CTR_PAYMENT.GLOBAL_TX_ID IS '납입 접수 거래 UUID (멱등성 키, 전체에서 유일)';
COMMENT ON COLUMN TB_CTR_PAYMENT.PAID_DT IS '납입 일시';

COMMENT ON TABLE TB_CMN_SEQ IS '채번 테이블. (번호유형, 상품구분, 연도)마다 마지막 일련번호를 둔다. 연도가 바뀌면 1부터 다시 시작';
COMMENT ON COLUMN TB_CMN_SEQ.SEQ_TYPE IS '번호유형 (POL:증권번호, APP:청약번호, CLM:사고번호)';
COMMENT ON COLUMN TB_CMN_SEQ.PROD_CD IS '상품구분 (CAR:자동차, FIRE:화재). 상품코드(TB_PRD.PRD_CD)와는 다르다';
COMMENT ON COLUMN TB_CMN_SEQ.SEQ_YEAR IS '채번 연도 (YYYY, 업무 시간대 기준)';
COMMENT ON COLUMN TB_CMN_SEQ.LAST_NO IS '마지막으로 발급한 일련번호';

-- 상품 ---------------------------------------------------------------------------------------------
COMMENT ON TABLE TB_PRD IS '상품. 판매 기간 안에서만 신계약을 받는다 (마이그레이션으로만 관리)';
COMMENT ON COLUMN TB_PRD.PRD_CD IS '상품코드 (예: CAR-STD-2026)';
COMMENT ON COLUMN TB_PRD.PRD_NM IS '상품명';
COMMENT ON COLUMN TB_PRD.INSR_CLS_CD IS '보험종목코드 (01:자동차, 02:화재)';
COMMENT ON COLUMN TB_PRD.PRD_DESC IS '상품 설명';
COMMENT ON COLUMN TB_PRD.SALE_FROM IS '판매 시작일 (포함)';
COMMENT ON COLUMN TB_PRD.SALE_TO IS '판매 종료일 (포함). null 이면 판매 중';

COMMENT ON TABLE TB_PRD_CVRG IS '상품별 담보 구성. 주계약은 반드시 가입, 특약은 필수 표시가 없으면 선택';
COMMENT ON COLUMN TB_PRD_CVRG.PRD_CD IS '상품코드 (TB_PRD)';
COMMENT ON COLUMN TB_PRD_CVRG.CVRG_CD IS '담보코드';
COMMENT ON COLUMN TB_PRD_CVRG.CVRG_TYPE IS '담보 구분 (MAIN:주계약, RIDER:특약)';
COMMENT ON COLUMN TB_PRD_CVRG.REQUIRED_YN IS '반드시 가입해야 하는지 (주계약은 항상 true)';
COMMENT ON COLUMN TB_PRD_CVRG.MIN_ISRD_AMT IS '최소 가입금액 (원)';
COMMENT ON COLUMN TB_PRD_CVRG.MAX_ISRD_AMT IS '최대 가입금액 (원)';
COMMENT ON COLUMN TB_PRD_CVRG.DEDUCTIBLE_AMT IS '자기부담금 (사고 1건당 지급액에서 뺀다, 원)';
COMMENT ON COLUMN TB_PRD_CVRG.SORT_ORD IS '화면 표시 순서';

-- 요율 ---------------------------------------------------------------------------------------------
COMMENT ON TABLE TB_PRM_RATE IS '담보별 기본 연 요율 (적용 기간별). 같은 담보의 기간은 겹칠 수 없다(트리거)';
COMMENT ON COLUMN TB_PRM_RATE.RATE_ID IS '요율 일련번호';
COMMENT ON COLUMN TB_PRM_RATE.INSR_CLS_CD IS '보험종목코드 (01:자동차, 02:화재)';
COMMENT ON COLUMN TB_PRM_RATE.CVRG_CD IS '담보코드';
COMMENT ON COLUMN TB_PRM_RATE.CVRG_NM IS '담보명';
COMMENT ON COLUMN TB_PRM_RATE.ANNUAL_RATE IS '연 요율. 가입금액에 곱하면 1년 보험료 (예: 0.005 = 0.5%). 개발용 예시 값';
COMMENT ON COLUMN TB_PRM_RATE.EFF_FROM IS '적용 시작일 (포함, 보험시기 기준으로 찾는다)';
COMMENT ON COLUMN TB_PRM_RATE.EFF_TO IS '적용 종료일 (포함). null 이면 현재까지 유효';

COMMENT ON TABLE TB_PRM_FACTOR IS '요율 계수 (적용 기간별). 기본 요율에 곱한다. 범위형(나이 구간)과 키형(등급 코드)이 있다';
COMMENT ON COLUMN TB_PRM_FACTOR.FACTOR_ID IS '계수 일련번호';
COMMENT ON COLUMN TB_PRM_FACTOR.INSR_CLS_CD IS '보험종목코드 (01:자동차, 02:화재)';
COMMENT ON COLUMN TB_PRM_FACTOR.FACTOR_TYPE IS '계수 종류 (DRIVER_AGE:운전자 나이, DISCOUNT_GRADE:할인할증 등급, STRUCTURE_GRADE:건물 구조 등급)';
COMMENT ON COLUMN TB_PRM_FACTOR.FACTOR_KEY IS '키형 계수의 코드 (예: 할인할증 1등급 = 1). 범위형이면 null';
COMMENT ON COLUMN TB_PRM_FACTOR.RANGE_FROM IS '범위형 계수의 시작 값 (포함, 예: 나이 18). 키형이면 null';
COMMENT ON COLUMN TB_PRM_FACTOR.RANGE_TO IS '범위형 계수의 끝 값 (포함, 예: 나이 25). 키형이면 null';
COMMENT ON COLUMN TB_PRM_FACTOR.FACTOR_NM IS '계수 이름 (화면 표시용)';
COMMENT ON COLUMN TB_PRM_FACTOR.FACTOR_VALUE IS '계수 값 (1.0 = 영향 없음, 0.8 = 20% 할인)';
COMMENT ON COLUMN TB_PRM_FACTOR.EFF_FROM IS '적용 시작일 (포함)';
COMMENT ON COLUMN TB_PRM_FACTOR.EFF_TO IS '적용 종료일 (포함). null 이면 현재까지 유효';

COMMENT ON TABLE TB_PRM_FACTOR_POLICY IS '필수 요율 계수 정책 (적용 기간별). 필수인 계수를 입력하지 않으면 산출·청약이 E-PRM-005 로 거절된다';
COMMENT ON COLUMN TB_PRM_FACTOR_POLICY.POLICY_ID IS '정책 일련번호';
COMMENT ON COLUMN TB_PRM_FACTOR_POLICY.INSR_CLS_CD IS '보험종목코드 (01:자동차, 02:화재)';
COMMENT ON COLUMN TB_PRM_FACTOR_POLICY.FACTOR_TYPE IS '계수 종류 (DRIVER_AGE, DISCOUNT_GRADE, STRUCTURE_GRADE)';
COMMENT ON COLUMN TB_PRM_FACTOR_POLICY.REQUIRED_YN IS '이 기간에 입력이 필수인지';
COMMENT ON COLUMN TB_PRM_FACTOR_POLICY.EFF_FROM IS '적용 시작일 (포함, 보험시기 기준으로 찾는다)';
COMMENT ON COLUMN TB_PRM_FACTOR_POLICY.EFF_TO IS '적용 종료일 (포함). null 이면 현재까지 유효';

COMMENT ON TABLE TB_PRM_AUDIT IS '요율·계수·필수 계수 정책 변경 감사 이력 (추가만 함)';
COMMENT ON COLUMN TB_PRM_AUDIT.AUDIT_ID IS '감사 일련번호';
COMMENT ON COLUMN TB_PRM_AUDIT.TARGET_TYPE IS '대상 종류 (RATE:요율, FACTOR:요율 계수, POLICY:필수 계수 정책)';
COMMENT ON COLUMN TB_PRM_AUDIT.TARGET_KEY IS '대상 식별 (예: 01/CA001, 01/DRIVER_AGE/18-25, 01/DISCOUNT_GRADE/1)';
COMMENT ON COLUMN TB_PRM_AUDIT.ACTION IS '동작 (CREATE:처음 만듦, REVISE:기존 행을 닫고 새 행 추가)';
COMMENT ON COLUMN TB_PRM_AUDIT.BEFORE_VAL IS '변경 전 값 (요율·계수·필수 여부). 처음 만든 경우 null';
COMMENT ON COLUMN TB_PRM_AUDIT.AFTER_VAL IS '변경 후 값';
COMMENT ON COLUMN TB_PRM_AUDIT.EFF_FROM IS '새 값의 적용 시작일';
COMMENT ON COLUMN TB_PRM_AUDIT.EMP_ID IS '처리 사번';
COMMENT ON COLUMN TB_PRM_AUDIT.GLOBAL_TX_ID IS '거래 UUID (대상 종류와 함께 유니크, 재전송 판별에 쓴다)';
COMMENT ON COLUMN TB_PRM_AUDIT.CHG_DT IS '처리 일시';

-- 보상 ---------------------------------------------------------------------------------------------
COMMENT ON TABLE TB_CLM IS '사고·보상 원장. 사고 접수부터 손해사정, 지급·면책·취소까지';
COMMENT ON COLUMN TB_CLM.CLM_NO IS '사고번호 (CLM-{상품구분}-{연도}-{6자리 일련번호})';
COMMENT ON COLUMN TB_CLM.CONTRACT_NO IS '증권번호 (TB_CTR_LGD)';
COMMENT ON COLUMN TB_CLM.CLM_STAT_CD IS '보상 상태코드 (10:접수, 20:손해사정 완료(지급 대기), 40:지급 완료, 80:면책, 90:접수 취소)';
COMMENT ON COLUMN TB_CLM.ACDT_DT IS '사고일 (보장 기간 안이어야 하고 미래일 수 없다)';
COMMENT ON COLUMN TB_CLM.ACDT_DESC IS '사고 내용';
COMMENT ON COLUMN TB_CLM.CLAIMANT_ID IS '청구인 ID';
COMMENT ON COLUMN TB_CLM.TOTAL_CLAIM_AMT IS '청구 손해액 합계 (원)';
COMMENT ON COLUMN TB_CLM.TOTAL_PAY_AMT IS '지급액 합계 (손해사정 후, 원). 접수 상태는 null, 면책·취소는 0';
COMMENT ON COLUMN TB_CLM.RPT_DT IS '접수 일시';
COMMENT ON COLUMN TB_CLM.RPT_EMP_ID IS '접수 사번';
COMMENT ON COLUMN TB_CLM.ADJ_EMP_ID IS '손해사정 사번 (다시 사정하면 마지막 사번)';
COMMENT ON COLUMN TB_CLM.ADJ_DT IS '손해사정 일시';
COMMENT ON COLUMN TB_CLM.DECIDE_EMP_ID IS '종결(지급·면책·취소) 처리 사번';
COMMENT ON COLUMN TB_CLM.DECIDE_DT IS '종결 일시';
COMMENT ON COLUMN TB_CLM.DECIDE_MEMO IS '종결 메모 (면책 사유 등)';
COMMENT ON COLUMN TB_CLM.GLOBAL_TX_ID IS '접수 거래 UUID (멱등성 키, 유일)';
COMMENT ON COLUMN TB_CLM.REQ_HASH IS '접수 요청 내용 지문. 같은 거래 UUID 재전송이 같은 내용인지 비교한다';

COMMENT ON TABLE TB_CLM_CVRG IS '사고별 담보 금액 (청구·사정·자기부담금·지급)';
COMMENT ON COLUMN TB_CLM_CVRG.CLM_NO IS '사고번호 (TB_CLM)';
COMMENT ON COLUMN TB_CLM_CVRG.CVRG_CD IS '담보코드 (계약이 가입한 담보만)';
COMMENT ON COLUMN TB_CLM_CVRG.CLAIM_AMT IS '청구 손해액 (원)';
COMMENT ON COLUMN TB_CLM_CVRG.ASSESSED_AMT IS '손해사정액 (원). 사정 전 null';
COMMENT ON COLUMN TB_CLM_CVRG.DEDUCTIBLE_AMT IS '적용한 자기부담금 (상품 담보 구성 값, 상품 없는 계약은 0, 원). 사정 전 null';
COMMENT ON COLUMN TB_CLM_CVRG.PAY_AMT IS '지급액 = min(max(사정액 − 자기부담금, 0), 가입금액 − 이미 지급한 금액) (원). 사정 전 null';

COMMENT ON TABLE TB_CLM_HIST IS '보상 상태 변경 이력 (추가만 함, (사고번호, 거래 UUID) 유니크)';
COMMENT ON COLUMN TB_CLM_HIST.HIST_ID IS '이력 일련번호 (발생 순서)';
COMMENT ON COLUMN TB_CLM_HIST.CLM_NO IS '사고번호 (TB_CLM)';
COMMENT ON COLUMN TB_CLM_HIST.BEFORE_STAT_CD IS '변경 전 보상 상태코드. 접수는 null';
COMMENT ON COLUMN TB_CLM_HIST.AFTER_STAT_CD IS '변경 후 보상 상태코드 (10, 20, 40, 80, 90)';
COMMENT ON COLUMN TB_CLM_HIST.EMP_ID IS '처리 사번';
COMMENT ON COLUMN TB_CLM_HIST.MEMO IS '처리 메모 (손해사정 의견, 면책 사유 등)';
COMMENT ON COLUMN TB_CLM_HIST.GLOBAL_TX_ID IS '변경을 일으킨 거래 UUID';
COMMENT ON COLUMN TB_CLM_HIST.CHG_DT IS '변경 일시';

-- 지급준비금 결산. 결산 기준일(분기말)에 보험종목별로 개별추산액(접수됐지만 아직 지급하지 않은 사고의 추정액)과
-- IBNR(이미 났지만 아직 접수되지 않았거나 덜 진전된 손해의 추정액)을 계산해 확정 값으로 남긴다.
-- 지급준비금 = 개별추산액 + IBNR. 결산은 배치(reserveCloseJob)가 기준일당 한 번만 하고, 확정한 값은 고치지 않는다(정정은 다음 결산에서).
-- IBNR 은 지급 기준 체인래더와 본휴터-퍼거슨(BF)으로 계산하고, 진전이 덜 된 사고 기간은 BF 를 쓴다. 계산 규칙은 ReserveService 주석 참고.

CREATE TABLE TB_RSV_CLOSE (
    CLOSE_DT          DATE           NOT NULL,
    INSR_CLS_CD       VARCHAR(2)     NOT NULL,
    PERIOD_TYPE       VARCHAR(10)    NOT NULL,
    PERIOD_COUNT      INTEGER        NOT NULL,
    PAID_AMT          NUMERIC(15, 0) NOT NULL,
    CASE_RSV          NUMERIC(15, 0) NOT NULL,
    IBNR_CL           NUMERIC(15, 0) NOT NULL,
    IBNR_BF           NUMERIC(15, 0),
    IBNR_AMT          NUMERIC(15, 0) NOT NULL,
    TOTAL_RSV         NUMERIC(15, 0) NOT NULL,
    ELR               NUMERIC(8, 4),
    TAIL_FACTOR       NUMERIC(8, 4)  NOT NULL,
    JOB_EXECUTION_ID  BIGINT         NOT NULL,
    CLOSE_TM          TIMESTAMP      NOT NULL,
    CLOSE_EMP_ID      VARCHAR(64),
    CONSTRAINT PK_RSV_CLOSE PRIMARY KEY (CLOSE_DT, INSR_CLS_CD),
    CONSTRAINT CK_RSV_CLOSE_AMT CHECK (CASE_RSV >= 0 AND IBNR_AMT >= 0 AND TOTAL_RSV = CASE_RSV + IBNR_AMT)
);
COMMENT ON TABLE TB_RSV_CLOSE IS '지급준비금 결산 (결산 기준일·보험종목별 확정 값). 지급준비금 = 개별추산액 + IBNR';
COMMENT ON COLUMN TB_RSV_CLOSE.CLOSE_DT IS '결산 기준일 (분기 말일)';
COMMENT ON COLUMN TB_RSV_CLOSE.INSR_CLS_CD IS '보험종목코드 (01:자동차, 02:화재, 03:운전자, 04:해외여행, 05:실손의료)';
COMMENT ON COLUMN TB_RSV_CLOSE.PERIOD_TYPE IS '사고 기간 단위 (QUARTER:분기)';
COMMENT ON COLUMN TB_RSV_CLOSE.PERIOD_COUNT IS '삼각형에 쓴 사고 기간 수';
COMMENT ON COLUMN TB_RSV_CLOSE.PAID_AMT IS '기준일까지 지급한 보험금 합계 (원, 삼각형에 든 사고 기간)';
COMMENT ON COLUMN TB_RSV_CLOSE.CASE_RSV IS '개별추산액 (원): 기준일에 미결인 사고의 추정액. 손해사정 완료면 지급 예정액, 접수만 됐으면 청구액';
COMMENT ON COLUMN TB_RSV_CLOSE.IBNR_CL IS '체인래더로 계산한 IBNR 합계 (원, 사고 기간별 음수는 0으로 본 값)';
COMMENT ON COLUMN TB_RSV_CLOSE.IBNR_BF IS '본휴터-퍼거슨으로 계산한 IBNR 합계 (원). 기대손해율을 정하지 못하면 null';
COMMENT ON COLUMN TB_RSV_CLOSE.IBNR_AMT IS '적립 IBNR (원): 사고 기간마다 진전율 50% 이상은 체인래더, 미만은 BF 를 골라 더한 값';
COMMENT ON COLUMN TB_RSV_CLOSE.TOTAL_RSV IS '지급준비금 (원) = 개별추산액 + 적립 IBNR';
COMMENT ON COLUMN TB_RSV_CLOSE.ELR IS 'BF 에 쓴 기대손해율 (케이프코드 방식으로 실적에서 구한 값, 예 0.6500)';
COMMENT ON COLUMN TB_RSV_CLOSE.TAIL_FACTOR IS '꼬리 진전계수 (삼각형 마지막 경과 이후의 진전, 1.0000 이면 없음)';
COMMENT ON COLUMN TB_RSV_CLOSE.JOB_EXECUTION_ID IS '결산한 배치 실행 일련번호 (BATCH_JOB_EXECUTION)';
COMMENT ON COLUMN TB_RSV_CLOSE.CLOSE_TM IS '결산 처리 일시';
COMMENT ON COLUMN TB_RSV_CLOSE.CLOSE_EMP_ID IS '결산 실행자 (사번, 예약 실행이면 system)';

CREATE TABLE TB_RSV_CLOSE_DTL (
    CLOSE_DT       DATE           NOT NULL,
    INSR_CLS_CD    VARCHAR(2)     NOT NULL,
    ACDT_PERIOD    VARCHAR(7)     NOT NULL,
    PERIOD_FROM    DATE           NOT NULL,
    PERIOD_TO      DATE           NOT NULL,
    AGE            INTEGER        NOT NULL,
    EARNED_PREM    NUMERIC(15, 0) NOT NULL,
    PAID_AMT       NUMERIC(15, 0) NOT NULL,
    CASE_RSV       NUMERIC(15, 0) NOT NULL,
    CDF            NUMERIC(12, 6) NOT NULL,
    CL_ULT         NUMERIC(15, 0) NOT NULL,
    BF_ULT         NUMERIC(15, 0),
    METHOD         VARCHAR(2)     NOT NULL,
    IBNR_AMT       NUMERIC(15, 0) NOT NULL,
    CONSTRAINT PK_RSV_CLOSE_DTL PRIMARY KEY (CLOSE_DT, INSR_CLS_CD, ACDT_PERIOD),
    CONSTRAINT FK_RSV_CLOSE_DTL FOREIGN KEY (CLOSE_DT, INSR_CLS_CD) REFERENCES TB_RSV_CLOSE (CLOSE_DT, INSR_CLS_CD),
    CONSTRAINT CK_RSV_CLOSE_DTL_METHOD CHECK (METHOD IN ('CL', 'BF'))
);
COMMENT ON TABLE TB_RSV_CLOSE_DTL IS '지급준비금 결산의 사고 기간별 내역 (IBNR 근거)';
COMMENT ON COLUMN TB_RSV_CLOSE_DTL.CLOSE_DT IS '결산 기준일 (TB_RSV_CLOSE)';
COMMENT ON COLUMN TB_RSV_CLOSE_DTL.INSR_CLS_CD IS '보험종목코드 (TB_RSV_CLOSE)';
COMMENT ON COLUMN TB_RSV_CLOSE_DTL.ACDT_PERIOD IS '사고 기간 (예: 2026Q3)';
COMMENT ON COLUMN TB_RSV_CLOSE_DTL.PERIOD_FROM IS '사고 기간 시작일';
COMMENT ON COLUMN TB_RSV_CLOSE_DTL.PERIOD_TO IS '사고 기간 종료일 (포함)';
COMMENT ON COLUMN TB_RSV_CLOSE_DTL.AGE IS '기준일의 경과 (사고 기간이 끝난 뒤 지난 기간 수 + 1)';
COMMENT ON COLUMN TB_RSV_CLOSE_DTL.EARNED_PREM IS '사고 기간의 경과보험료 (원, 손해율 분석과 같은 정의)';
COMMENT ON COLUMN TB_RSV_CLOSE_DTL.PAID_AMT IS '기준일까지 지급한 보험금 (원)';
COMMENT ON COLUMN TB_RSV_CLOSE_DTL.CASE_RSV IS '기준일의 개별추산액 (원)';
COMMENT ON COLUMN TB_RSV_CLOSE_DTL.CDF IS '누적 진전계수 (이 경과부터 최종까지, 꼬리 포함)';
COMMENT ON COLUMN TB_RSV_CLOSE_DTL.CL_ULT IS '체인래더 최종손해액 (원) = 지급액 × 누적 진전계수';
COMMENT ON COLUMN TB_RSV_CLOSE_DTL.BF_ULT IS 'BF 최종손해액 (원) = 지급액 + 기대손해율 × 경과보험료 × (1 − 1/누적 진전계수)';
COMMENT ON COLUMN TB_RSV_CLOSE_DTL.METHOD IS '적립에 고른 방법 (CL:체인래더, BF:본휴터-퍼거슨)';
COMMENT ON COLUMN TB_RSV_CLOSE_DTL.IBNR_AMT IS '적립 IBNR (원) = max(0, 최종손해액 − 지급액 − 개별추산액)';

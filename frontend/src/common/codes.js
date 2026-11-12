// 공통 코드. 백엔드의 코드값(DB 시드, enum)과 같은 값을 쓴다.

export const INSR_CLS = [
  { code: '01', name: '자동차' },
  { code: '02', name: '화재' },
  { code: '03', name: '운전자' },
  { code: '04', name: '해외여행' },
  { code: '05', name: '실손의료' },
];

/** 실손(05)은 상품 필수, 청약에 신용정보 조회 동의 필수 (V26) */
export const MEDICAL_CLS = '05';

export const CTR_STAT = [
  { code: '10', name: '청약', tone: 'info' },
  { code: '20', name: '유효', tone: 'ok' },
  { code: '30', name: '해지', tone: 'muted' },
  { code: '90', name: '청약취소', tone: 'danger' },
];

/** 보험종목별 담보 (V7 시드 기준). 요율 개정으로 담보명이 바뀌면 산출 응답의 cvrgNm 을 우선 쓴다. */
export const COVERAGES = {
  '01': [
    { code: 'CA001', name: '대인배상' },
    { code: 'CA002', name: '대물배상' },
    { code: 'CA003', name: '자기신체사고' },
    { code: 'CA004', name: '자기차량손해' },
  ],
  '02': [
    { code: 'FI001', name: '건물' },
    { code: 'FI002', name: '가재도구' },
    { code: 'FI003', name: '시설소유자배상책임' },
  ],
  '03': [
    { code: 'DR001', name: '교통사고처리지원금' },
    { code: 'DR002', name: '변호사선임비용' },
    { code: 'DR003', name: '벌금' },
    { code: 'DR004', name: '운전자상해(사망)' },
  ],
  '04': [
    { code: 'TR001', name: '해외상해의료비' },
    { code: 'TR002', name: '해외질병의료비' },
    { code: 'TR003', name: '휴대품손해' },
    { code: 'TR004', name: '해외배상책임' },
    { code: 'TR005', name: '항공기·수하물지연' },
  ],
  '05': [
    { code: 'ME001', name: '급여 실손의료비' },
    { code: 'ME002', name: '비급여 실손의료비' },
  ],
};

/** 보험종목별 요율 계수 입력 항목 (V8 시드 기준) */
export const RATING_FIELDS = {
  '01': [
    { key: 'driverAge', label: '운전자 만 나이', type: 'number', placeholder: '예: 35', min: 18, max: 99 },
    {
      key: 'discountGrade',
      label: '할인할증 등급',
      type: 'select',
      options: [
        { code: '1', name: '1등급 (최우량 할인)' },
        { code: '2', name: '2등급 (우량 할인)' },
        { code: '3', name: '3등급 (기본)' },
        { code: '4', name: '4등급 (할증)' },
        { code: '5', name: '5등급 (고할증)' },
      ],
    },
  ],
  '02': [
    {
      key: 'structureGrade',
      label: '건물 구조 등급',
      type: 'select',
      options: [
        { code: '1', name: '1급 (내화구조)' },
        { code: '2', name: '2급 (준내화구조)' },
        { code: '3', name: '3급 (비내화구조)' },
      ],
    },
  ],
  '03': [
    { key: 'driverAge', label: '운전자 만 나이', type: 'number', placeholder: '예: 40', min: 18, max: 99 },
  ],
  '04': [
    {
      key: 'travelRegion',
      label: '여행 지역',
      type: 'select',
      options: [
        { code: 'ASIA', name: '아시아' },
        { code: 'EUROPE', name: '유럽' },
        { code: 'AMERICA', name: '미주' },
        { code: 'ETC', name: '그 밖' },
      ],
    },
    { key: 'insuredAge', label: '피보험자 만 나이', type: 'number', placeholder: '예: 30 (80세 이상 가입 불가)', min: 0, max: 79 },
  ],
  '05': [
    { key: 'insuredAge', label: '피보험자 만 나이', type: 'number', placeholder: '예: 35 (80세 이상 가입 불가)', min: 0, max: 79 },
  ],
};

/** 실손 진료 구분과 요양기관 종별 (TB_CLM_MED_ITEM) */
export const CARE_TYPES = [
  { code: 'OUT', name: '통원' },
  { code: 'IN', name: '입원' },
];
export const FACILITY_TYPES = [
  { code: 'CLINIC', name: '의원' },
  { code: 'HOSPITAL', name: '병원' },
  { code: 'GENERAL', name: '종합병원' },
  { code: 'TERTIARY', name: '상급종합병원' },
];
/** 중복가입 조회 상태 (TB_CTR_MED_CHECK) */
export const MED_CHECK_STAT = [
  { code: '10', name: '조회 완료', tone: 'ok' },
  { code: '80', name: '조회 실패 (재조회 필요)', tone: 'danger' },
];

/** 판매채널 (계약 원장 SALES_CHNL_CD, V23). 채널은 토큰으로 정해지고 화면에서 고르지 않는다 */
export const SALES_CHNL = [
  { code: '10', name: '대면', tone: 'muted' },
  { code: '20', name: '다이렉트', tone: 'info' },
  { code: '30', name: 'TM', tone: 'info' },
];

export const FACTOR_TYPES = [
  { code: 'DRIVER_AGE', name: '운전자 나이', insrClsCd: '01' },
  { code: 'TRAVEL_REGION', name: '여행 지역', insrClsCd: '04' },
  { code: 'INSURED_AGE', name: '피보험자 나이 (해외여행·실손)', insrClsCd: '04' },
  { code: 'DISCOUNT_GRADE', name: '할인할증 등급', insrClsCd: '01' },
  { code: 'STRUCTURE_GRADE', name: '건물 구조 등급', insrClsCd: '02' },
  { code: 'SALES_CHANNEL', name: '판매채널 (서버가 정함)' },
];

export const PAY_METHODS = [
  { code: 'TRANSFER', name: '계좌이체' },
  { code: 'CARD', name: '카드' },
  { code: 'CASH', name: '현금' },
];

export const ROLES = [
  { code: 'AGENT', name: '설계사', desc: '청약 접수, 본인 계약 조회·갱신' },
  { code: 'UNDERWRITER', name: '심사', desc: '청약 승인·취소, 배서' },
  { code: 'OPERATOR', name: '계약관리', desc: '수납, 해지, 배서' },
  { code: 'CLAIM', name: '보상', desc: '손해사정, 보험금 지급·면책' },
  { code: 'ADMIN', name: '관리자', desc: '모든 업무, 요율·사원 관리' },
];

export const TODO_TYPES = [
  { code: 'PENDING_APPROVAL', name: '승인 대기', desc: '보험료를 완납해 승인할 수 있는 청약' },
  { code: 'UNPAID', name: '미납 청약', desc: '아직 보험료를 다 내지 않은 청약' },
  { code: 'STALE_APPLICATION', name: '장기 미처리', desc: '접수 후 7일 이상 청약 상태인 건' },
];

export const CLM_STAT = [
  { code: '10', name: '접수', tone: 'info' },
  { code: '20', name: '지급 대기', tone: 'warn' },
  { code: '40', name: '지급 완료', tone: 'ok' },
  { code: '80', name: '면책', tone: 'danger' },
  { code: '90', name: '접수 취소', tone: 'muted' },
];

export const CVRG_TYPES = [
  { code: 'MAIN', name: '주계약', tone: 'info' },
  { code: 'RIDER', name: '특약', tone: 'muted' },
];

export const LOGIN_RESULTS = [
  { code: 'SUCCESS', name: '성공', tone: 'ok' },
  { code: 'BAD_CREDENTIALS', name: '비밀번호 오류', tone: 'warn' },
  { code: 'LOCKED', name: '잠김', tone: 'danger' },
  { code: 'DISABLED', name: '사용 중지', tone: 'muted' },
];

export const AUDIT_TARGETS = [
  { code: 'RATE', name: '요율' },
  { code: 'FACTOR', name: '계수' },
  { code: 'POLICY', name: '필수 계수 정책' },
];

/** 코드 목록에서 이름을 찾는다. 없으면 코드를 그대로 보여 준다. */
export function nameOf(list, code) {
  return list.find((item) => item.code === code)?.name ?? code ?? '-';
}

export function coverageName(cvrgCd) {
  for (const list of Object.values(COVERAGES)) {
    const found = list.find((c) => c.code === cvrgCd);
    if (found) return found.name;
  }
  return cvrgCd;
}

/** 지급 상태 (TB_PAY_OUT, V29) */
export const PAYOUT_STAT = [
  { code: '10', name: '이체 대기', tone: 'info' },
  { code: '30', name: '이체 완료', tone: 'ok' },
  { code: '70', name: '결과 불명 (조회 중)', tone: 'warn' },
  { code: '80', name: '실패', tone: 'danger' },
];

/** 은행 코드 (모의 은행 예시값) */
export const BANKS = [
  { code: '004', name: '국민' },
  { code: '011', name: '농협' },
  { code: '020', name: '우리' },
  { code: '088', name: '신한' },
];

/** 대사 결과 (TB_PAY_RECON) */
export const RECON_RESULTS = [
  { code: 'MATCHED', name: '일치', tone: 'ok' },
  { code: 'STATUS_DIFF', name: '상태 다름 (이중 지급 위험)', tone: 'danger' },
  { code: 'OURS_ONLY', name: '우리만 완료', tone: 'danger' },
  { code: 'BANK_ONLY', name: '은행에만 있음', tone: 'danger' },
  { code: 'AMOUNT_DIFF', name: '금액 다름', tone: 'warn' },
];

/** FEP 전문 결과 (TB_FEP_MSG_LOG) */
export const FEP_RESULTS = [
  { code: 'OK', name: '정상', tone: 'ok' },
  { code: 'ERROR', name: '오류 응답', tone: 'warn' },
  { code: 'TIMEOUT', name: '응답 없음', tone: 'danger' },
  { code: 'BLOCKED', name: '보내지 않음', tone: 'muted' },
];

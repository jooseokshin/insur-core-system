// 공통 코드. 백엔드의 코드값(DB 시드, enum)과 같은 값을 쓴다.

export const INSR_CLS = [
  { code: '01', name: '자동차' },
  { code: '02', name: '화재' },
];

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
};

/** 보험종목별 요율 계수 입력 항목 (V8 시드 기준) */
export const RATING_FIELDS = {
  '01': [
    { key: 'driverAge', label: '운전자 만 나이', type: 'number', placeholder: '예: 35' },
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
};

export const FACTOR_TYPES = [
  { code: 'DRIVER_AGE', name: '운전자 나이', insrClsCd: '01' },
  { code: 'DISCOUNT_GRADE', name: '할인할증 등급', insrClsCd: '01' },
  { code: 'STRUCTURE_GRADE', name: '건물 구조 등급', insrClsCd: '02' },
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
  { code: 'ADMIN', name: '관리자', desc: '모든 업무, 요율·사원 관리' },
];

export const TODO_TYPES = [
  { code: 'PENDING_APPROVAL', name: '승인 대기', desc: '보험료를 완납해 승인할 수 있는 청약' },
  { code: 'UNPAID', name: '미납 청약', desc: '아직 보험료를 다 내지 않은 청약' },
  { code: 'STALE_APPLICATION', name: '장기 미처리', desc: '접수 후 7일 이상 청약 상태인 건' },
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

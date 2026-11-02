// 좌측 메뉴. roles 가 없으면 모든 업무 역할이 본다. 메뉴를 숨겨도 실제 권한은 서버가 다시 확인한다.

const ALL = ['AGENT', 'UNDERWRITER', 'OPERATOR', 'ADMIN'];
const TARIFF_VIEWERS = ['UNDERWRITER', 'OPERATOR', 'ADMIN'];

export const MENU = [
  {
    group: '업무',
    items: [
      { path: '/', label: '업무 현황', roles: ALL },
      { path: '/todo', label: '할 일함', roles: ALL },
    ],
  },
  {
    group: '계약',
    items: [
      { path: '/contracts', label: '계약 조회', roles: ALL },
      { path: '/contracts/apply', label: '청약 입력', roles: ['AGENT', 'ADMIN'] },
      { path: '/quote', label: '보험료 산출', roles: ALL },
      { path: '/renewals', label: '만기·갱신 대상', roles: ALL },
    ],
  },
  {
    group: '상품·요율',
    items: [
      { path: '/tariff/rates', label: '담보 요율', roles: TARIFF_VIEWERS },
      { path: '/tariff/factors', label: '요율 계수', roles: TARIFF_VIEWERS },
      { path: '/tariff/policies', label: '필수 계수 정책', roles: TARIFF_VIEWERS },
      { path: '/tariff/audits', label: '요율 변경 이력', roles: TARIFF_VIEWERS },
    ],
  },
  {
    group: '시스템 관리',
    items: [
      { path: '/admin/employees', label: '사원·권한 관리', roles: ['ADMIN'] },
      { path: '/admin/login-history', label: '로그인 이력', roles: ['ADMIN'] },
    ],
  },
];

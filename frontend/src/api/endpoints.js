// 업무별 API 목록. 경로와 바디 모양은 백엔드 DTO 와 1:1 로 맞춘다.
import { call } from './http';

export const authApi = {
  /** 본인 정보. 로그인 직후에는 아직 세션이 없어서 받은 토큰을 직접 넘긴다. */
  me: (token) => call('/auth/me', {}, token ? { token } : {}),
  changePassword: (currentPassword, newPassword) => call('/auth/password', { currentPassword, newPassword }),
};

export const workApi = {
  dashboard: () => call('/api/work/dashboard'),
  todo: (type, page = 0, size = 20) => call('/api/work/todo', { type, page, size }),
  renewalTargets: (cond) => call('/api/work/renewal-targets', cond),
};

export const contractApi = {
  list: (cond) => call('/api/contract/list', cond),
  inquiry: (contractNo) => call('/api/contract/inquiry', { contractNo }),
  apply: (body, txId) => call('/api/contract/apply', body, { txId }),
  statusChange: (contractNo, ctrStatCd, txId) => call('/api/contract/status-change', { contractNo, ctrStatCd }, { txId }),
  statusHistory: (contractNo, page = 0, size = 50) => call('/api/contract/status-history', { contractNo, page, size }),
  payment: (contractNo, payAmt, payMethod, txId) => call('/api/contract/payment', { contractNo, payAmt, payMethod }, { txId }),
  paymentHistory: (contractNo, page = 0, size = 50) => call('/api/contract/payment-history', { contractNo, page, size }),
  endorse: (contractNo, policyholderId, insuredId, txId) =>
    call('/api/contract/endorse', { contractNo, policyholderId, insuredId }, { txId }),
  endorseHistory: (contractNo, page = 0, size = 50) => call('/api/contract/endorse-history', { contractNo, page, size }),
  renewQuote: (contractNo, rating) => call('/api/contract/renew-quote', { contractNo, rating }),
  renew: (contractNo, rating, txId) => call('/api/contract/renew', { contractNo, rating }, { txId }),
};

export const productApi = {
  list: (cond = {}) => call('/api/product/list', cond),
};

export const claimApi = {
  report: (body, txId) => call('/api/claim/report', body, { txId }),
  assess: (clmNo, coverages, memo, txId) => call('/api/claim/assess', { clmNo, coverages, memo }, { txId }),
  decide: (clmNo, decision, memo, txId) => call('/api/claim/decide', { clmNo, decision, memo }, { txId }),
  inquiry: (clmNo) => call('/api/claim/inquiry', { clmNo }),
  list: (cond) => call('/api/claim/list', cond),
};

export const statsApi = {
  lossRatio: (cond) => call('/api/stats/loss-ratio', cond),
};

export const premiumApi = {
  quote: (body) => call('/api/premium/quote', body),
};

export const tariffApi = {
  rateList: (cond) => call('/api/admin/premium/rate-list', cond),
  rateRevise: (body, txId) => call('/api/admin/premium/rate-revise', body, { txId }),
  factorList: (cond) => call('/api/admin/premium/factor-list', cond),
  factorRevise: (body, txId) => call('/api/admin/premium/factor-revise', body, { txId }),
  policyList: (cond) => call('/api/admin/premium/policy-list', cond),
  policyRevise: (body, txId) => call('/api/admin/premium/policy-revise', body, { txId }),
  auditList: (cond) => call('/api/admin/premium/audit-list', cond),
};

export const employeeApi = {
  list: (cond) => call('/auth/admin/emp-list', cond),
  detail: (empId) => call('/auth/admin/emp-detail', { empId }),
  create: (body, txId) => call('/auth/admin/emp-create', body, { txId }),
  update: (body, txId) => call('/auth/admin/emp-update', body, { txId }),
  resetPassword: (empId, txId) => call('/auth/admin/emp-reset-password', { empId }, { txId }),
  unlock: (empId, txId) => call('/auth/admin/emp-unlock', { empId }, { txId }),
  loginHistory: (cond) => call('/auth/admin/login-history', cond),
};

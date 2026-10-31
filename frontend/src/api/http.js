// 공통 전문(header + body) 규약으로 백엔드를 호출하는 클라이언트.
// 모든 업무 API 는 POST 이고, 응답 header.responseCode 가 '0000' 이 아니면 ApiError 로 던진다.

const SUCCESS = '0000';

/** 백엔드가 돌려준 업무 오류. code 는 E-CTR-010 같은 업무 오류코드다. */
export class ApiError extends Error {
  constructor(code, message, status, globalTxId) {
    super(message);
    this.name = 'ApiError';
    this.code = code;
    this.status = status;
    this.globalTxId = globalTxId;
  }
}

let tokenProvider = () => null;
let unauthorizedHandler = () => {};

/** 인증 모듈이 현재 토큰을 넘겨주는 함수를 등록한다. */
export function setTokenProvider(fn) {
  tokenProvider = fn;
}

/** 토큰이 만료되었거나 위조되어 401 이 오면 호출할 함수(로그아웃 처리)를 등록한다. */
export function setUnauthorizedHandler(fn) {
  unauthorizedHandler = fn;
}

/** 거래 UUID. 같은 업무를 다시 보낼 때(재시도) 같은 값을 쓰면 서버가 한 번만 처리한다. */
export function newTxId() {
  return crypto.randomUUID();
}

/**
 * 업무 API 를 호출하고 응답 바디를 돌려준다.
 * @param {string} path  /api/... 또는 /auth/...
 * @param {object} [body] 요청 바디
 * @param {{ txId?: string, auth?: boolean }} [options] txId: 거래 UUID(재시도 시 같은 값), auth: 토큰을 붙일지(기본 true)
 */
export async function call(path, body = {}, { txId = newTxId(), auth = true } = {}) {
  const headers = { 'Content-Type': 'application/json' };
  const token = auth ? tokenProvider() : null;
  if (token) headers.Authorization = `Bearer ${token}`;

  let response;
  try {
    response = await fetch(path, {
      method: 'POST',
      headers,
      body: JSON.stringify({ header: { globalTxId: txId }, body }),
    });
  } catch {
    throw new ApiError('E-NET-001', '서버에 연결할 수 없습니다. 네트워크 상태를 확인하고 다시 시도해 주세요.', 0, txId);
  }

  let message = null;
  try {
    message = await response.json();
  } catch {
    // 프록시 오류 페이지처럼 전문이 아닌 응답
  }
  const header = message?.header;
  if (!header) {
    throw new ApiError('E-NET-002', `서버 응답을 해석할 수 없습니다. (HTTP ${response.status})`, response.status, txId);
  }
  if (header.responseCode !== SUCCESS) {
    const error = new ApiError(header.responseCode, header.responseMsg, response.status, header.globalTxId);
    if (response.status === 401 && auth) unauthorizedHandler(error);
    throw error;
  }
  return message.body;
}

// OAuth 2.1 Authorization Code + PKCE 클라이언트 (공개 클라이언트, 비밀값 없음).
// 비밀번호는 이 화면이 받지 않는다. 인증 서버의 로그인 화면으로 보냈다가, 돌아온 인가 코드를 토큰으로 바꾼다.
//
//   1) beginLogin: code_verifier(무작위) 와 state 를 sessionStorage 에 두고, verifier 의 SHA-256(code_challenge)을 실어 인가 요청
//   2) 인증 서버 로그인 → /callback?code=...&state=...
//   3) completeLogin: state 가 같은지 확인하고, code + code_verifier 로 토큰 엔드포인트에서 토큰을 받는다
// 코드를 가로채도 verifier 가 없으면 토큰으로 못 바꾸는 것이 PKCE 다. crypto.subtle 은 HTTPS(또는 127.0.0.1) 에서만 동작한다.

export const OAUTH = {
  clientId: 'insur-web',
  authorizeUrl: '/auth/oauth2/authorize',
  tokenUrl: '/auth/oauth2/token',
  logoutUrl: '/auth/connect/logout',
  scope: 'openid profile',
};

const PENDING_KEY = 'insur.oauth.pending';

const redirectUri = () => `${window.location.origin}/callback`;

function base64Url(bytes) {
  let text = '';
  bytes.forEach((b) => { text += String.fromCharCode(b); });
  return btoa(text).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

function randomString(byteLength = 32) {
  return base64Url(crypto.getRandomValues(new Uint8Array(byteLength)));
}

async function challengeOf(verifier) {
  const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(verifier));
  return base64Url(new Uint8Array(digest));
}

/** 인증 서버 로그인으로 보낸다. from: 로그인 뒤 돌아올 화면 경로 */
export async function beginLogin(from = '/') {
  const verifier = randomString(48);
  const state = randomString(16);
  sessionStorage.setItem(PENDING_KEY, JSON.stringify({ verifier, state, from }));
  const params = new URLSearchParams({
    response_type: 'code',
    client_id: OAUTH.clientId,
    redirect_uri: redirectUri(),
    scope: OAUTH.scope,
    state,
    code_challenge: await challengeOf(verifier),
    code_challenge_method: 'S256',
  });
  window.location.assign(`${OAUTH.authorizeUrl}?${params}`);
}

export class OAuthError extends Error {
  constructor(code, message) {
    super(message);
    this.name = 'OAuthError';
    this.code = code;
  }
}

/**
 * /callback 의 쿼리를 받아 토큰으로 바꾼다. 한 번 쓴 verifier 는 지운다(같은 코드를 두 번 교환하지 않게).
 * @returns {Promise<{ accessToken: string, idToken: string, expiresAt: string, from: string }>}
 */
export async function completeLogin(search) {
  const query = new URLSearchParams(search);
  let pending = null;
  try {
    pending = JSON.parse(sessionStorage.getItem(PENDING_KEY));
  } catch {
    // 저장소를 못 읽으면 아래에서 state 불일치로 처리
  }
  sessionStorage.removeItem(PENDING_KEY);

  if (query.get('error')) {
    throw new OAuthError(query.get('error'), query.get('error_description') ?? '로그인이 취소되었거나 거절되었습니다.');
  }
  if (!pending || !query.get('state') || pending.state !== query.get('state')) {
    throw new OAuthError('state_mismatch', '로그인 요청 정보가 맞지 않습니다. 다시 로그인해 주세요.');
  }

  let response;
  try {
    response = await fetch(OAUTH.tokenUrl, {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams({
        grant_type: 'authorization_code',
        code: query.get('code') ?? '',
        redirect_uri: redirectUri(),
        client_id: OAUTH.clientId,
        code_verifier: pending.verifier,
      }),
    });
  } catch {
    throw new OAuthError('network', '인증 서버에 연결할 수 없습니다.');
  }
  const body = await response.json().catch(() => ({}));
  if (!response.ok) {
    // 계정 상태가 바뀐 경우(사용 중지·비밀번호 초기화) 인증 서버가 invalid_grant 와 업무 오류코드를 준다
    throw new OAuthError(body.error ?? 'token_error', body.error_description ?? '토큰을 받지 못했습니다. 다시 로그인해 주세요.');
  }
  return {
    accessToken: body.access_token,
    idToken: body.id_token,
    expiresAt: new Date(Date.now() + body.expires_in * 1000).toISOString(),
    from: pending.from ?? '/',
  };
}

/**
 * 인증 서버의 로그인 세션까지 끝낸다(OIDC RP-Initiated Logout). 이걸 안 하면 다음 로그인이 비밀번호 없이 지나간다.
 * 끝나면 인증 서버가 /login 으로 돌려보낸다.
 */
export function endServerSession(idToken) {
  const params = new URLSearchParams({ post_logout_redirect_uri: `${window.location.origin}/login` });
  if (idToken) params.set('id_token_hint', idToken);
  window.location.assign(`${OAUTH.logoutUrl}?${params}`);
}

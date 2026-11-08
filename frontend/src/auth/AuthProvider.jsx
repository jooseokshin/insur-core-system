import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState } from 'react';
import { authApi } from '../api/endpoints';
import { setTokenProvider, setUnauthorizedHandler } from '../api/http';
import { beginLogin, completeLogin, endServerSession } from './oauth';

// 로그인 세션. 토큰은 탭을 닫으면 사라지도록 sessionStorage 에 둔다(공용 PC 대비).
// 토큰 자체가 서버의 판단 근거이고, 여기 보관하는 역할 정보는 메뉴와 버튼을 보여 줄지 정하는 데만 쓴다.
// 토큰은 OAuth 인가 코드 흐름으로만 받는다(auth/oauth.js). 갱신 토큰이 없어서, 만료되면 로그인 흐름을 다시 탄다.
// 인증 서버의 로그인 세션(기본 30분)이 남아 있으면 비밀번호를 다시 묻지 않고 바로 돌아온다.

const STORAGE_KEY = 'insur.session';
const NOTICE_KEY = 'insur.notice';
const AuthContext = createContext(null);

function loadSession() {
  try {
    const saved = JSON.parse(sessionStorage.getItem(STORAGE_KEY));
    if (saved && new Date(saved.expiresAt).getTime() > Date.now()) return saved;
  } catch {
    // 저장소를 못 쓰면 로그인부터 다시
  }
  return null;
}

function saveSession(session) {
  try {
    if (session) sessionStorage.setItem(STORAGE_KEY, JSON.stringify(session));
    else sessionStorage.removeItem(STORAGE_KEY);
  } catch {
    // 무시: 새로고침하면 다시 로그인한다
  }
}

/** 화면을 떠났다 돌아와도(로그아웃 리다이렉트) 안내 문구가 남도록 sessionStorage 에 잠깐 둔다 */
function takeNotice() {
  try {
    const notice = sessionStorage.getItem(NOTICE_KEY);
    sessionStorage.removeItem(NOTICE_KEY);
    return notice;
  } catch {
    return null;
  }
}

function keepNotice(notice) {
  try {
    if (notice) sessionStorage.setItem(NOTICE_KEY, notice);
  } catch {
    // 무시
  }
}

export function AuthProvider({ children }) {
  const [session, setSession] = useState(loadSession);
  const [notice, setNotice] = useState(takeNotice);

  const update = useCallback((next) => {
    saveSession(next);
    setSession(next);
  }, []);

  /** 이 화면의 토큰만 버린다. 인증 서버 로그인 세션은 남으므로 다시 로그인하면 비밀번호 없이 돌아올 수 있다. */
  const expire = useCallback((reason) => {
    update(null);
    setNotice(reason ?? null);
  }, [update]);

  /** 사용자가 누른 로그아웃: 토큰을 버리고 인증 서버 로그인 세션도 끝낸다. */
  const logout = useCallback(() => {
    const idToken = session?.idToken;
    update(null);
    keepNotice('로그아웃했습니다.');
    endServerSession(idToken);
  }, [session, update]);

  // 화면의 첫 조회(자식 컴포넌트의 effect)가 이 컴포넌트의 effect 보다 먼저 돌기 때문에, 토큰 공급자는 렌더링 중에 바로 연결한다.
  const sessionRef = useRef(session);
  sessionRef.current = session;
  setTokenProvider(() => sessionRef.current?.token ?? null);
  setUnauthorizedHandler(() => expire('인증이 만료되었습니다. 다시 로그인해 주세요.'));

  // 토큰 만료 시각이 되면 이 화면의 토큰을 버린다
  useEffect(() => {
    if (!session) return undefined;
    const remain = new Date(session.expiresAt).getTime() - Date.now();
    const timer = setTimeout(() => expire('로그인 유효시간이 지났습니다. 다시 로그인해 주세요.'), Math.max(remain, 0));
    return () => clearTimeout(timer);
  }, [session, expire]);

  const login = useCallback((from) => {
    setNotice(null);
    return beginLogin(from);
  }, []);

  /** /callback 에서 부른다. 토큰을 받고 본인 정보(역할)를 조회해 세션을 만든다. 돌아갈 경로를 돌려준다. */
  const finishLogin = useCallback(async (search) => {
    const tokens = await completeLogin(search);
    const profile = await authApi.me(tokens.accessToken);
    update({ token: tokens.accessToken, idToken: tokens.idToken, expiresAt: tokens.expiresAt, profile });
    setNotice(null);
    return tokens.from;
  }, [update]);

  const changePassword = useCallback(async (current, next) => {
    const profile = await authApi.changePassword(current, next);
    if (sessionRef.current) update({ ...sessionRef.current, profile });
    return profile;
  }, [update]);

  const value = useMemo(() => {
    const roles = session?.profile?.roles ?? [];
    return {
      session,
      profile: session?.profile ?? null,
      roles,
      notice,
      clearNotice: () => setNotice(null),
      /** 주어진 역할 중 하나라도 있으면 true */
      hasAny: (...wanted) => wanted.some((r) => roles.includes(r)),
      /** AGENT 만 가진 사용자: 백엔드가 본인 접수 계약으로 제한한다 */
      ownContractsOnly: roles.includes('AGENT') && !roles.some((r) => r !== 'AGENT'),
      login,
      finishLogin,
      logout,
      changePassword,
    };
  }, [session, notice, login, finishLogin, logout, changePassword]);

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth() {
  return useContext(AuthContext);
}

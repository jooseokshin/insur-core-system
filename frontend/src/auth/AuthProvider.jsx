import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState } from 'react';
import { authApi } from '../api/endpoints';
import { setTokenProvider, setUnauthorizedHandler } from '../api/http';

// 로그인 세션. 토큰은 탭을 닫으면 사라지도록 sessionStorage 에 둔다(공용 PC 대비).
// 토큰 자체가 서버의 판단 근거이고, 여기 보관하는 역할 정보는 메뉴와 버튼을 보여 줄지 정하는 데만 쓴다.

const STORAGE_KEY = 'insur.session';
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

/** 로그인·비밀번호 변경 응답(TokenResponse)을 세션으로 바꾼다. */
function toSession(res) {
  return {
    token: res.accessToken,
    expiresAt: res.expiresAt,
    passwordChangeRequired: res.passwordChangeRequired,
    profile: res.profile,
  };
}

export function AuthProvider({ children }) {
  const [session, setSession] = useState(loadSession);
  const [notice, setNotice] = useState(null);

  const update = useCallback((next) => {
    saveSession(next);
    setSession(next);
  }, []);

  const logout = useCallback((reason) => {
    update(null);
    setNotice(reason ?? null);
  }, [update]);

  // 화면의 첫 조회(자식 컴포넌트의 effect)가 이 컴포넌트의 effect 보다 먼저 돌기 때문에, 토큰 공급자는 렌더링 중에 바로 연결한다.
  const sessionRef = useRef(session);
  sessionRef.current = session;
  setTokenProvider(() => sessionRef.current?.token ?? null);
  setUnauthorizedHandler(() => logout('인증이 만료되었습니다. 다시 로그인해 주세요.'));

  // 토큰 만료 시각이 되면 자동으로 로그아웃한다
  useEffect(() => {
    if (!session) return undefined;
    const remain = new Date(session.expiresAt).getTime() - Date.now();
    const timer = setTimeout(() => logout('로그인 유효시간이 지나 자동으로 로그아웃되었습니다.'), Math.max(remain, 0));
    return () => clearTimeout(timer);
  }, [session, logout]);

  const login = useCallback(async (empId, password) => {
    const res = await authApi.login(empId, password);
    setNotice(null);
    update(toSession(res));
    return res;
  }, [update]);

  const changePassword = useCallback(async (current, next) => {
    const res = await authApi.changePassword(current, next);
    update(toSession(res));
    return res;
  }, [update]);

  const value = useMemo(() => {
    const roles = session?.passwordChangeRequired ? [] : (session?.profile?.roles ?? []);
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
      logout,
      changePassword,
    };
  }, [session, notice, login, logout, changePassword]);

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth() {
  return useContext(AuthContext);
}

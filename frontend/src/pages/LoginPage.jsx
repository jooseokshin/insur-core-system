import { useState } from 'react';
import { Navigate, useLocation } from 'react-router-dom';
import { useAuth } from '../auth/AuthProvider';

/**
 * 로그인 시작 화면. 사번·비밀번호는 여기서 받지 않고 인증 서버의 로그인 화면에서 입력한다(OAuth 인가 코드 + PKCE).
 * 인증 서버 로그인 세션이 살아 있으면 비밀번호 없이 바로 돌아온다.
 */
export function LoginPage() {
  const { session, login, notice } = useAuth();
  const location = useLocation();
  const [busy, setBusy] = useState(false);
  const from = location.state?.from ?? '/';
  const error = location.state?.error;

  if (session) return <Navigate to={from} replace />;

  const start = async () => {
    setBusy(true);
    try {
      await login(from);
    } catch {
      setBusy(false);
    }
  };

  return (
    <div className="login-screen">
      <div className="login-side">
        <div className="login-brand">
          <span className="brand-mark">INSUR</span>
          <h1>손해보험 기간계</h1>
          <p>청약 접수부터 심사, 수납, 배서, 해지, 갱신까지 계약의 전 과정을 처리하는 업무 시스템입니다.</p>
        </div>
        <ul className="login-notes">
          <li>사번과 비밀번호는 시스템 관리자가 발급합니다.</li>
          <li>비밀번호를 5회 연속 틀리면 계정이 잠깁니다.</li>
          <li>업무 중 자리를 비울 때는 반드시 로그아웃해 주세요.</li>
        </ul>
      </div>
      <div className="login-card">
        <h2>로그인</h2>
        {notice && <div className="alert alert-info">{notice}</div>}
        {error && <div className="alert alert-error" role="alert">{error}</div>}
        <p className="muted">사번과 비밀번호는 인증 서버의 로그인 화면에서 입력합니다. 이 화면은 비밀번호를 받지 않습니다.</p>
        <button id="login-start" type="button" className="btn btn-primary btn-block" onClick={start} disabled={busy}>
          {busy ? '로그인 화면으로 이동 중…' : '사내 계정으로 로그인'}
        </button>
      </div>
    </div>
  );
}

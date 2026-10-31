import { useState } from 'react';
import { Navigate, useLocation, useNavigate } from 'react-router-dom';
import { useAuth } from '../auth/AuthProvider';
import { ErrorBox } from '../components/ui';

export function LoginPage() {
  const { session, login, notice, clearNotice } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const [empId, setEmpId] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);

  if (session) return <Navigate to={session.passwordChangeRequired ? '/password' : '/'} replace />;

  const submit = async (e) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    clearNotice();
    try {
      const res = await login(empId.trim(), password);
      navigate(res.passwordChangeRequired ? '/password' : (location.state?.from ?? '/'), { replace: true });
    } catch (err) {
      setError(err);
      setPassword('');
    } finally {
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
      <form className="login-card" onSubmit={submit}>
        <h2>로그인</h2>
        {notice && <div className="alert alert-info">{notice}</div>}
        <ErrorBox error={error} />
        <label className="field">
          <span className="field-label">사번</span>
          <input id="login-emp-id" value={empId} onChange={(e) => setEmpId(e.target.value)} autoComplete="username"
                 autoFocus required maxLength={20} />
        </label>
        <label className="field">
          <span className="field-label">비밀번호</span>
          <input id="login-password" type="password" value={password} onChange={(e) => setPassword(e.target.value)}
                 autoComplete="current-password" required />
        </label>
        <button type="submit" className="btn btn-primary btn-block" disabled={busy}>
          {busy ? '확인 중…' : '로그인'}
        </button>
        {import.meta.env.DEV && (
          <p className="login-dev">
            개발 계정: admin01 · agent01 · uw01 · op01 / Insur!2026 (인증 서버 local 프로필)
          </p>
        )}
      </form>
    </div>
  );
}

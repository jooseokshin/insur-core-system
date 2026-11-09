import { useEffect, useRef } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { useAuth } from '../auth/AuthProvider';

/** 인증 서버가 인가 코드를 붙여 돌려보내는 곳(/callback). 코드를 토큰으로 바꾸고 원래 화면으로 간다. */
export function CallbackPage() {
  const { finishLogin } = useAuth();
  const location = useLocation();
  const navigate = useNavigate();
  // 개발 모드(StrictMode)는 effect 를 두 번 돌린다. 인가 코드는 한 번만 쓸 수 있으므로 한 번만 교환한다.
  const started = useRef(false);

  useEffect(() => {
    if (started.current) return;
    started.current = true;
    finishLogin(location.search)
      .then((from) => navigate(from, { replace: true }))
      .catch((err) => navigate('/login', { replace: true, state: { error: err.message } }));
  }, [finishLogin, location.search, navigate]);

  return (
    <div className="login-screen single">
      <div className="login-card"><p className="muted">로그인을 마치는 중…</p></div>
    </div>
  );
}

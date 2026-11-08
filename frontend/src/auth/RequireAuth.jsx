import { Navigate, useLocation } from 'react-router-dom';
import { useAuth } from './AuthProvider';

/** 로그인하지 않았으면 로그인 화면으로 보낸다. (임시 비밀번호 변경은 인증 서버 화면에서 끝내고 온다) */
export function RequireAuth({ children }) {
  const { session } = useAuth();
  const location = useLocation();
  if (!session) {
    return <Navigate to="/login" replace state={{ from: location.pathname + location.search }} />;
  }
  return children;
}

/** 역할이 없으면 화면 대신 안내를 보여 준다. 실제 권한 확인은 서버가 한다. */
export function RequireRole({ roles, children }) {
  const { hasAny } = useAuth();
  if (!hasAny(...roles)) {
    return (
      <div className="empty-state">
        <h2>이 화면을 볼 권한이 없습니다</h2>
        <p>필요한 역할이 있다면 시스템 관리자에게 요청해 주세요.</p>
      </div>
    );
  }
  return children;
}

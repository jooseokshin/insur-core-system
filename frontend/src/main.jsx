import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { BrowserRouter } from 'react-router-dom';
import { App } from './App';
import { AuthProvider } from './auth/AuthProvider';
import { ToastProvider } from './components/ui';
import './styles.css';

// 인증 서버는 돌아갈 주소로 localhost 를 받지 않는다(OAuth 2.1 보안 권고: 루프백은 IP 로). 개발 중 localhost 로 열면 같은 주소의 127.0.0.1 로 옮긴다.
// 토큰은 출처(origin)별 sessionStorage 에 있으므로 한 출처로 통일해야 로그인 상태가 이어진다.
if (window.location.hostname === 'localhost') {
  window.location.replace(window.location.href.replace('//localhost', '//127.0.0.1'));
} else {
  createRoot(document.getElementById('root')).render(
    <StrictMode>
      <BrowserRouter>
        <AuthProvider>
          <ToastProvider>
            <App />
          </ToastProvider>
        </AuthProvider>
      </BrowserRouter>
    </StrictMode>,
  );
}

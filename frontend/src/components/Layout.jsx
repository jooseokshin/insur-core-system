import { useEffect, useState } from 'react';
import { NavLink, Outlet, useNavigate } from 'react-router-dom';
import { useAuth } from '../auth/AuthProvider';
import { ROLES, nameOf } from '../common/codes';
import { MENU } from '../common/menu';

/** 남은 로그인 시간 (분:초) */
function useRemaining(expiresAt) {
  const [now, setNow] = useState(Date.now());
  useEffect(() => {
    const t = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(t);
  }, []);
  const ms = Math.max(new Date(expiresAt).getTime() - now, 0);
  const m = Math.floor(ms / 60000);
  const s = Math.floor((ms % 60000) / 1000);
  return { text: `${m}:${String(s).padStart(2, '0')}`, warn: ms < 5 * 60000 };
}

export function Layout() {
  const { profile, roles, session, logout } = useAuth();
  const navigate = useNavigate();
  const remaining = useRemaining(session.expiresAt);
  const [menuOpen, setMenuOpen] = useState(false);

  const groups = MENU.map((g) => ({ ...g, items: g.items.filter((i) => i.roles.some((r) => roles.includes(r))) }))
    .filter((g) => g.items.length > 0);

  return (
    <div className="shell">
      <header className="topbar">
        <button type="button" className="menu-toggle" onClick={() => setMenuOpen((v) => !v)} aria-label="메뉴 열기">
          ☰
        </button>
        <div className="brand" onClick={() => navigate('/')} role="link" tabIndex={0}
             onKeyDown={(e) => e.key === 'Enter' && navigate('/')}>
          <span className="brand-mark">INSUR</span>
          <span className="brand-sub">손해보험 기간계</span>
        </div>
        <div className="topbar-right">
          <span className={`session-timer ${remaining.warn ? 'warn' : ''}`} title="로그인 남은 시간">
            ⏱ {remaining.text}
          </span>
          <span className="user">
            <strong>{profile.empNm}</strong>
            <span className="user-meta">
              {profile.empId}
              {profile.deptNm ? ` · ${profile.deptNm}` : ''}
            </span>
          </span>
          <span className="user-roles">{roles.map((r) => nameOf(ROLES, r)).join(' · ')}</span>
          <NavLink to="/account/password" className="topbar-link">
            비밀번호 변경
          </NavLink>
          <button type="button" className="btn btn-ghost-light" onClick={() => logout()}>
            로그아웃
          </button>
        </div>
      </header>
      <div className="body">
        <nav className={`sidebar ${menuOpen ? 'open' : ''}`} onClick={() => setMenuOpen(false)}>
          {groups.map((g) => (
            <div key={g.group} className="nav-group">
              <div className="nav-group-title">{g.group}</div>
              {g.items.map((i) => (
                <NavLink key={i.path} to={i.path} end={i.path === '/' || i.path === '/contracts'}
                         className={({ isActive }) => `nav-item ${isActive ? 'active' : ''}`}>
                  {i.label}
                </NavLink>
              ))}
            </div>
          ))}
        </nav>
        <main className="content">
          <Outlet />
        </main>
      </div>
    </div>
  );
}

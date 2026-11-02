import { Navigate, Route, Routes } from 'react-router-dom';
import { RequireAuth, RequireRole } from './auth/RequireAuth';
import { Layout } from './components/Layout';
import { LoginPage } from './pages/LoginPage';
import { PasswordPage } from './pages/PasswordPage';
import { DashboardPage } from './pages/work/DashboardPage';
import { TodoPage } from './pages/work/TodoPage';
import { RenewalTargetsPage } from './pages/work/RenewalTargetsPage';
import { ContractListPage } from './pages/contract/ContractListPage';
import { ContractDetailPage } from './pages/contract/ContractDetailPage';
import { ApplyPage } from './pages/contract/ApplyPage';
import { QuotePage } from './pages/contract/QuotePage';
import { FactorPage, PolicyPage, RatePage, TariffAuditPage } from './pages/admin/TariffPages';
import { EmployeePage, LoginHistoryPage } from './pages/admin/EmployeePages';
import { useAuth } from './auth/AuthProvider';

const TARIFF = ['UNDERWRITER', 'OPERATOR', 'ADMIN'];

/**
 * 비밀번호 변경이 필요한 사원만 쓰는 메뉴 없는 단독 화면. 변경을 마치면 세션이 바뀌면서 바로 업무 현황으로 간다.
 * (평소의 비밀번호 변경은 레이아웃 안의 /account/password 를 쓴다)
 */
function PasswordRoute() {
  const { session } = useAuth();
  if (session?.passwordChangeRequired) return <PasswordPage />;
  return <Navigate to="/" replace />;
}

export function App() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route path="/password" element={<RequireAuth><PasswordRoute /></RequireAuth>} />
      <Route element={<RequireAuth><Layout /></RequireAuth>}>
        <Route index element={<DashboardPage />} />
        <Route path="todo" element={<TodoPage />} />
        <Route path="renewals" element={<RenewalTargetsPage />} />
        <Route path="contracts" element={<ContractListPage />} />
        <Route path="contracts/apply" element={<RequireRole roles={['AGENT', 'ADMIN']}><ApplyPage /></RequireRole>} />
        <Route path="contracts/:contractNo" element={<ContractDetailPage />} />
        <Route path="quote" element={<QuotePage />} />
        <Route path="tariff/rates" element={<RequireRole roles={TARIFF}><RatePage /></RequireRole>} />
        <Route path="tariff/factors" element={<RequireRole roles={TARIFF}><FactorPage /></RequireRole>} />
        <Route path="tariff/policies" element={<RequireRole roles={TARIFF}><PolicyPage /></RequireRole>} />
        <Route path="tariff/audits" element={<RequireRole roles={TARIFF}><TariffAuditPage /></RequireRole>} />
        <Route path="admin/employees" element={<RequireRole roles={['ADMIN']}><EmployeePage /></RequireRole>} />
        <Route path="admin/login-history" element={<RequireRole roles={['ADMIN']}><LoginHistoryPage /></RequireRole>} />
        <Route path="account/password" element={<PasswordPage />} />
        <Route path="*" element={<div className="empty-state"><h2>없는 화면입니다</h2><p>주소를 확인해 주세요.</p></div>} />
      </Route>
    </Routes>
  );
}

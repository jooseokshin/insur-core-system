import { Navigate, Route, Routes } from 'react-router-dom';
import { RequireAuth, RequireRole } from './auth/RequireAuth';
import { Layout } from './components/Layout';
import { LoginPage } from './pages/LoginPage';
import { PasswordPage } from './pages/PasswordPage';
import { CallbackPage } from './pages/CallbackPage';
import { DashboardPage } from './pages/work/DashboardPage';
import { TodoPage } from './pages/work/TodoPage';
import { RenewalTargetsPage } from './pages/work/RenewalTargetsPage';
import { ContractListPage } from './pages/contract/ContractListPage';
import { ContractDetailPage } from './pages/contract/ContractDetailPage';
import { ApplyPage } from './pages/contract/ApplyPage';
import { QuotePage } from './pages/contract/QuotePage';
import { ProductPage } from './pages/contract/ProductPage';
import { LossRatioPage } from './pages/admin/LossRatioPage';
import { RateImpactPage } from './pages/admin/RateImpactPage';
import { ReservePage } from './pages/admin/ReservePage';
import { OpsPage } from './pages/admin/OpsPage';
import { ClaimDetailPage, ClaimListPage } from './pages/claim/ClaimPages';
import { FactorPage, PolicyPage, RatePage, TariffAuditPage } from './pages/admin/TariffPages';
import { EmployeePage, LoginHistoryPage } from './pages/admin/EmployeePages';

const TARIFF = ['UNDERWRITER', 'OPERATOR', 'ADMIN'];
const CLAIM_USERS = ['AGENT', 'CLAIM', 'ADMIN'];

export function App() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route path="/callback" element={<CallbackPage />} />
      <Route element={<RequireAuth><Layout /></RequireAuth>}>
        <Route index element={<DashboardPage />} />
        <Route path="todo" element={<TodoPage />} />
        <Route path="renewals" element={<RenewalTargetsPage />} />
        <Route path="contracts" element={<ContractListPage />} />
        <Route path="contracts/apply" element={<RequireRole roles={['AGENT', 'ADMIN']}><ApplyPage /></RequireRole>} />
        <Route path="contracts/:contractNo" element={<ContractDetailPage />} />
        <Route path="quote" element={<QuotePage />} />
        <Route path="products" element={<ProductPage />} />
        <Route path="claims" element={<RequireRole roles={CLAIM_USERS}><ClaimListPage /></RequireRole>} />
        <Route path="claims/:clmNo" element={<RequireRole roles={CLAIM_USERS}><ClaimDetailPage /></RequireRole>} />
        <Route path="tariff/rates" element={<RequireRole roles={TARIFF}><RatePage /></RequireRole>} />
        <Route path="tariff/factors" element={<RequireRole roles={TARIFF}><FactorPage /></RequireRole>} />
        <Route path="tariff/policies" element={<RequireRole roles={TARIFF}><PolicyPage /></RequireRole>} />
        <Route path="tariff/audits" element={<RequireRole roles={TARIFF}><TariffAuditPage /></RequireRole>} />
        <Route path="stats/loss-ratio" element={<RequireRole roles={['UNDERWRITER', 'CLAIM', 'ADMIN']}><LossRatioPage /></RequireRole>} />
        <Route path="stats/rate-impact" element={<RequireRole roles={['UNDERWRITER', 'ADMIN']}><RateImpactPage /></RequireRole>} />
        <Route path="stats/reserve" element={<RequireRole roles={['UNDERWRITER', 'CLAIM', 'ADMIN']}><ReservePage /></RequireRole>} />
        <Route path="admin/ops" element={<RequireRole roles={['OPERATOR', 'ADMIN']}><OpsPage /></RequireRole>} />
        <Route path="admin/employees" element={<RequireRole roles={['ADMIN']}><EmployeePage /></RequireRole>} />
        <Route path="admin/login-history" element={<RequireRole roles={['ADMIN']}><LoginHistoryPage /></RequireRole>} />
        <Route path="account/password" element={<PasswordPage />} />
        <Route path="*" element={<div className="empty-state"><h2>없는 화면입니다</h2><p>주소를 확인해 주세요.</p></div>} />
      </Route>
    </Routes>
  );
}

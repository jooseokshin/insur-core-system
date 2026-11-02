import { Link } from 'react-router-dom';
import { workApi } from '../../api/endpoints';
import { useAuth } from '../../auth/AuthProvider';
import { CTR_STAT, TODO_TYPES, nameOf } from '../../common/codes';
import { money } from '../../common/format';
import { ErrorBox, PageHeader, Panel, StatusBadge, useLoad } from '../../components/ui';

function Kpi({ label, value, unit, sub, to }) {
  const body = (
    <>
      <span className="kpi-label">{label}</span>
      <span className="kpi-value">
        {value}
        {unit && <small>{unit}</small>}
      </span>
      {sub && <span className="kpi-sub">{sub}</span>}
    </>
  );
  return to ? <Link to={to} className="kpi kpi-link">{body}</Link> : <div className="kpi">{body}</div>;
}

export function DashboardPage() {
  const { profile, ownContractsOnly } = useAuth();
  const { data, error, loading, reload } = useLoad(() => workApi.dashboard(), []);

  const statusRows = CTR_STAT.map((s) => {
    const found = data?.statusCounts.find((c) => c.ctrStatCd === s.code);
    return { code: s.code, count: found?.count ?? 0, premium: found?.totalPremium ?? 0 };
  });
  const totalCount = statusRows.reduce((sum, r) => sum + r.count, 0);

  return (
    <div className="page">
      <PageHeader
        title="업무 현황"
        desc={`${profile.empNm}님, ${data ? data.baseDate : ''} 기준 업무 현황입니다.${ownContractsOnly ? ' 본인이 접수한 계약만 집계합니다.' : ''}`}
        actions={<button type="button" className="btn" onClick={reload}>새로고침</button>}
      />
      <ErrorBox error={error} />
      {loading && !data && <p className="muted">불러오는 중…</p>}
      {data && (
        <>
          <div className="kpi-row">
            <Kpi label="오늘 청약 접수" value={data.today.appliedCount} unit="건" sub={`보험료 ${money(data.today.appliedPremium)}원`} />
            <Kpi label="오늘 승인" value={data.today.approvedCount} unit="건"
                 sub={`취소 ${data.today.canceledCount}건 · 해지 ${data.today.terminatedCount}건`} />
            <Kpi label="오늘 수납" value={money(data.today.paidAmount)} unit="원" sub={`${data.today.paidCount}건`} />
            <Kpi label="갱신 대상 (60일 이내 만기)" value={data.renewalDueCount} unit="건" sub="만기·갱신 대상 보기 →" to="/renewals" />
          </div>

          <div className="two-col">
            <Panel title="처리할 일" actions={<Link to="/todo" className="link">할 일함 →</Link>}>
              {data.todoCounts.length === 0 && <p className="muted">현재 역할로 처리할 할 일 유형이 없습니다.</p>}
              <ul className="todo-summary">
                {data.todoCounts.map((t) => (
                  <li key={t.type}>
                    <Link to={`/todo?type=${t.type}`}>
                      <span>
                        <strong>{t.label}</strong>
                        <small>{TODO_TYPES.find((x) => x.code === t.type)?.desc}</small>
                      </span>
                      <span className={`todo-count ${t.count > 0 ? 'has' : ''}`}>{t.count}</span>
                    </Link>
                  </li>
                ))}
              </ul>
            </Panel>

            <Panel title="계약 상태별 현황">
              <table className="grid compact">
                <thead>
                  <tr>
                    <th>상태</th>
                    <th style={{ textAlign: 'right' }}>건수</th>
                    <th style={{ textAlign: 'right' }}>비율</th>
                    <th style={{ textAlign: 'right' }}>총보험료 합계</th>
                  </tr>
                </thead>
                <tbody>
                  {statusRows.map((r) => (
                    <tr key={r.code}>
                      <td>
                        <Link to={`/contracts?ctrStatCd=${r.code}`} className="plain-link">
                          <StatusBadge code={r.code} /> <span className="sr-only">{nameOf(CTR_STAT, r.code)}</span>
                        </Link>
                      </td>
                      <td className="num">{r.count.toLocaleString()}</td>
                      <td className="num">{totalCount ? `${((r.count / totalCount) * 100).toFixed(1)}%` : '-'}</td>
                      <td className="num">{money(r.premium)}</td>
                    </tr>
                  ))}
                </tbody>
                <tfoot>
                  <tr>
                    <td>합계</td>
                    <td className="num">{totalCount.toLocaleString()}</td>
                    <td className="num">{totalCount ? '100.0%' : '-'}</td>
                    <td className="num">{money(statusRows.reduce((s, r) => s + Number(r.premium), 0))}</td>
                  </tr>
                </tfoot>
              </table>
            </Panel>
          </div>
        </>
      )}
    </div>
  );
}

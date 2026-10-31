import { useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { workApi } from '../../api/endpoints';
import { useAuth } from '../../auth/AuthProvider';
import { INSR_CLS, TODO_TYPES, nameOf } from '../../common/codes';
import { date, dateTime, money } from '../../common/format';
import { DataTable, ErrorBox, PageHeader, Pagination, Panel, useLoad } from '../../components/ui';

/** 할 일 유형별 처리 역할 (백엔드 TodoType 과 같은 규칙) */
const VISIBLE = {
  PENDING_APPROVAL: ['UNDERWRITER', 'ADMIN'],
  UNPAID: ['AGENT', 'OPERATOR', 'ADMIN'],
  STALE_APPLICATION: ['AGENT', 'UNDERWRITER', 'ADMIN'],
};

export function TodoPage() {
  const { hasAny } = useAuth();
  const navigate = useNavigate();
  const [params, setParams] = useSearchParams();
  const tabs = TODO_TYPES.filter((t) => hasAny(...VISIBLE[t.code]));
  const type = tabs.some((t) => t.code === params.get('type')) ? params.get('type') : tabs[0]?.code;
  const [page, setPage] = useState(0);

  const { data, error, loading } = useLoad(() => (type ? workApi.todo(type, page) : Promise.resolve(null)), [type, page]);

  const select = (code) => {
    setPage(0);
    setParams({ type: code });
  };

  const columns = [
    { key: 'elapsedDays', title: '경과', width: 70, align: 'right', render: (r) => <span className={r.elapsedDays >= 7 ? 'text-danger' : ''}>{r.elapsedDays}일</span> },
    { key: 'contractNo', title: '증권번호', render: (r) => <span className="mono">{r.contractNo}</span> },
    { key: 'insrClsCd', title: '종목', width: 70, render: (r) => nameOf(INSR_CLS, r.insrClsCd) },
    { key: 'policyholderId', title: '계약자' },
    { key: 'startDate', title: '보험시기', render: (r) => date(r.startDate) },
    { key: 'totalPremium', title: '총보험료', align: 'right', render: (r) => money(r.totalPremium) },
    { key: 'paidPremium', title: '납입액', align: 'right', render: (r) => money(r.paidPremium) },
    { key: 'remainingPremium', title: '미납액', align: 'right', render: (r) => <strong className={Number(r.remainingPremium) > 0 ? 'text-danger' : 'text-ok'}>{money(r.remainingPremium)}</strong> },
    { key: 'sysRegDt', title: '접수일시', render: (r) => dateTime(r.sysRegDt) },
    { key: 'regEmpId', title: '접수자' },
  ];

  return (
    <div className="page">
      <PageHeader title="할 일함" desc="역할별로 처리해야 할 청약을 오래된 접수순으로 보여 줍니다. 행을 누르면 계약 상세에서 바로 처리할 수 있습니다." />
      {tabs.length === 0 ? (
        <Panel><p className="muted">현재 역할로 처리할 할 일 유형이 없습니다.</p></Panel>
      ) : (
        <Panel>
          <div className="tabs" role="tablist">
            {tabs.map((t) => (
              <button key={t.code} type="button" role="tab" aria-selected={t.code === type}
                      className={`tab ${t.code === type ? 'active' : ''}`} onClick={() => select(t.code)}>
                {t.name}
              </button>
            ))}
          </div>
          <p className="muted tab-desc">{TODO_TYPES.find((t) => t.code === type)?.desc}</p>
          <ErrorBox error={error} />
          <DataTable columns={columns} rows={data?.items} loading={loading} rowKey={(r) => r.contractNo}
                     onRowClick={(r) => navigate(`/contracts/${encodeURIComponent(r.contractNo)}`)}
                     empty="처리할 건이 없습니다." />
          {data && <Pagination page={data.page} totalPages={data.totalPages} totalElements={data.totalElements} onChange={setPage} />}
        </Panel>
      )}
    </div>
  );
}

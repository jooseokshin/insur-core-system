import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { workApi } from '../../api/endpoints';
import { useAuth } from '../../auth/AuthProvider';
import { INSR_CLS, nameOf } from '../../common/codes';
import { date, money } from '../../common/format';
import { DataTable, ErrorBox, Field, PageHeader, Pagination, Panel, Select, useLoad } from '../../components/ui';

const PERIODS = [
  { code: '7', name: '7일 이내' },
  { code: '15', name: '15일 이내' },
  { code: '30', name: '30일 이내' },
  { code: '60', name: '60일 이내 (갱신 가능 기간 전체)' },
];

export function RenewalTargetsPage() {
  const navigate = useNavigate();
  const { hasAny } = useAuth();
  const [withinDays, setWithinDays] = useState('60');
  const [insrClsCd, setInsrClsCd] = useState('');
  const [page, setPage] = useState(0);
  const canRenew = hasAny('AGENT', 'ADMIN');

  const { data, error, loading } = useLoad(
    () => workApi.renewalTargets({ withinDays: Number(withinDays), insrClsCd: insrClsCd || null, page, size: 20 }),
    [withinDays, insrClsCd, page],
  );

  const columns = [
    {
      key: 'daysToEnd', title: '만기까지', width: 90, align: 'right',
      render: (r) => <strong className={r.daysToEnd <= 7 ? 'text-danger' : r.daysToEnd <= 30 ? 'text-warn' : ''}>D-{r.daysToEnd}</strong>,
    },
    { key: 'contractNo', title: '증권번호', render: (r) => <span className="mono">{r.contractNo}</span> },
    { key: 'insrClsCd', title: '종목', width: 70, render: (r) => nameOf(INSR_CLS, r.insrClsCd) },
    { key: 'policyholderId', title: '계약자' },
    { key: 'insuredId', title: '피보험자' },
    { key: 'period', title: '보험기간', render: (r) => `${date(r.startDate)} ~ ${date(r.endDate)}` },
    { key: 'totalPremium', title: '현재 보험료', align: 'right', render: (r) => money(r.totalPremium) },
    { key: 'regEmpId', title: '접수자' },
    {
      key: 'action', title: '', width: 90, align: 'center',
      render: (r) => canRenew && (
        <button type="button" className="btn btn-sm btn-primary"
                onClick={(e) => { e.stopPropagation(); navigate(`/contracts/${encodeURIComponent(r.contractNo)}?action=renew`); }}>
          갱신
        </button>
      ),
    },
  ];

  return (
    <div className="page">
      <PageHeader title="만기·갱신 대상" desc="만기가 다가오는 유효 계약 중 아직 갱신하지 않은 건입니다. 보험종기 60일 전부터 종기까지 갱신할 수 있습니다." />
      <Panel>
        <div className="search-bar">
          <Field label="만기 기간">
            <Select value={withinDays} onChange={(v) => { setPage(0); setWithinDays(v); }} options={PERIODS} />
          </Field>
          <Field label="보험종목">
            <Select value={insrClsCd} onChange={(v) => { setPage(0); setInsrClsCd(v); }} options={INSR_CLS} placeholder="전체" />
          </Field>
        </div>
      </Panel>
      <Panel title={data ? `기준일 ${data.baseDate}` : undefined}>
        <ErrorBox error={error} />
        <DataTable columns={columns} rows={data?.items} loading={loading} rowKey={(r) => r.contractNo}
                   onRowClick={(r) => navigate(`/contracts/${encodeURIComponent(r.contractNo)}`)}
                   empty="기간 안에 만기가 오는 갱신 대상이 없습니다." />
        {data && <Pagination page={data.page} totalPages={data.totalPages} totalElements={data.totalElements} onChange={setPage} />}
      </Panel>
    </div>
  );
}

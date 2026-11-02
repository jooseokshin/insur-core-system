import { useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { contractApi } from '../../api/endpoints';
import { useAuth } from '../../auth/AuthProvider';
import { CTR_STAT, INSR_CLS, nameOf } from '../../common/codes';
import { date, dateTime, money } from '../../common/format';
import { DataTable, ErrorBox, Field, PageHeader, Pagination, Panel, Select, StatusBadge, useLoad } from '../../components/ui';

const SORTS = [
  { code: 'REG_DATE:DESC', name: '최근 접수순' },
  { code: 'REG_DATE:ASC', name: '오래된 접수순' },
  { code: 'START_DATE:DESC', name: '보험시기 늦은순' },
  { code: 'TOTAL_PREMIUM:DESC', name: '보험료 높은순' },
];

const EMPTY = { contractNo: '', policyholderId: '', insrClsCd: '', ctrStatCd: '', regDateFrom: '', regDateTo: '', sort: 'REG_DATE:DESC' };

export function ContractListPage() {
  const navigate = useNavigate();
  const { ownContractsOnly } = useAuth();
  const [params] = useSearchParams();
  const [form, setForm] = useState({ ...EMPTY, ctrStatCd: params.get('ctrStatCd') ?? '' });
  const [cond, setCond] = useState(form);
  const [page, setPage] = useState(0);
  const set = (patch) => setForm((f) => ({ ...f, ...patch }));

  const { data, error, loading } = useLoad(() => {
    const [sortBy, sortDir] = cond.sort.split(':');
    return contractApi.list({
      contractNo: cond.contractNo.trim() || null,
      policyholderId: cond.policyholderId.trim() || null,
      insrClsCd: cond.insrClsCd || null,
      ctrStatCd: cond.ctrStatCd || null,
      regDateFrom: cond.regDateFrom || null,
      regDateTo: cond.regDateTo || null,
      sortBy, sortDir, page, size: 20,
    });
  }, [cond, page]);

  const search = (e) => {
    e.preventDefault();
    setPage(0);
    setCond(form);
  };

  const columns = [
    { key: 'contractNo', title: '증권번호', render: (r) => <span className="mono">{r.contractNo}</span> },
    { key: 'insrClsCd', title: '종목', width: 70, render: (r) => nameOf(INSR_CLS, r.insrClsCd) },
    { key: 'ctrStatCd', title: '상태', width: 90, render: (r) => <StatusBadge code={r.ctrStatCd} /> },
    { key: 'policyholderId', title: '계약자' },
    { key: 'period', title: '보험기간', render: (r) => `${date(r.startDate)} ~ ${date(r.endDate)}` },
    { key: 'totalPremium', title: '총보험료', align: 'right', render: (r) => money(r.totalPremium) },
    { key: 'sysRegDt', title: '접수일시', render: (r) => dateTime(r.sysRegDt) },
    { key: 'regEmpId', title: '접수자' },
  ];

  return (
    <div className="page">
      <PageHeader title="계약 조회" desc={ownContractsOnly ? '설계사 권한은 본인이 접수한 계약만 조회됩니다.' : '조건을 넣고 조회하세요. 행을 누르면 상세로 이동합니다.'} />
      <Panel>
        <form className="search-bar" onSubmit={search}>
          <Field label="증권번호 (앞부분)">
            <input id="search-contract-no" value={form.contractNo} onChange={(e) => set({ contractNo: e.target.value })} placeholder="POL-CAR-2026" />
          </Field>
          <Field label="계약자 ID">
            <input value={form.policyholderId} onChange={(e) => set({ policyholderId: e.target.value })} />
          </Field>
          <Field label="보험종목">
            <Select value={form.insrClsCd} onChange={(v) => set({ insrClsCd: v })} options={INSR_CLS} placeholder="전체" />
          </Field>
          <Field label="계약상태">
            <Select id="search-status" value={form.ctrStatCd} onChange={(v) => set({ ctrStatCd: v })} options={CTR_STAT} placeholder="전체" />
          </Field>
          <Field label="접수일">
            <div className="range">
              <input type="date" value={form.regDateFrom} onChange={(e) => set({ regDateFrom: e.target.value })} />
              <span>~</span>
              <input type="date" value={form.regDateTo} onChange={(e) => set({ regDateTo: e.target.value })} />
            </div>
          </Field>
          <Field label="정렬">
            <Select value={form.sort} onChange={(v) => set({ sort: v })} options={SORTS} />
          </Field>
          <div className="search-actions">
            <button type="button" className="btn" onClick={() => setForm(EMPTY)}>초기화</button>
            <button type="submit" className="btn btn-primary">조회</button>
          </div>
        </form>
      </Panel>
      <Panel>
        <ErrorBox error={error} />
        <DataTable columns={columns} rows={data?.items} loading={loading} rowKey={(r) => r.contractNo}
                   onRowClick={(r) => navigate(`/contracts/${encodeURIComponent(r.contractNo)}`)} />
        {data && <Pagination page={data.page} totalPages={data.totalPages} totalElements={data.totalElements} onChange={setPage} />}
      </Panel>
    </div>
  );
}

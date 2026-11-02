import { useState } from 'react';
import { tariffApi } from '../../api/endpoints';
import { newTxId } from '../../api/http';
import { useAuth } from '../../auth/AuthProvider';
import { AUDIT_TARGETS, FACTOR_TYPES, INSR_CLS, nameOf } from '../../common/codes';
import { date, dateTime, seoulDate } from '../../common/format';
import { Badge, DataTable, ErrorBox, Field, Modal, PageHeader, Pagination, Panel, Select, useLoad, useToast } from '../../components/ui';

// 요율·계수·필수 계수 정책은 적용 기간(EFF_FROM~EFF_TO)이 있는 이력 테이블이다.
// 개정하면 서버가 직전 행을 (새 시작일 - 1)로 닫고 새 행을 넣는다. 조회는 심사·계약관리·관리자, 개정은 관리자만 한다.

const STATUS = { ACTIVE: ['적용 중', 'ok'], FUTURE: ['적용 예정', 'info'], CLOSED: ['종료', 'muted'] };

function EffStatus({ status }) {
  const [label, tone] = STATUS[status] ?? [status, 'muted'];
  return <Badge tone={tone}>{label}</Badge>;
}

function period(r) {
  return `${date(r.effFrom)} ~ ${r.effTo ? date(r.effTo) : ''}`;
}

/** 개정 대화상자 공통 틀. 거래 UUID 는 열릴 때 한 번 만든다. */
function ReviseModal({ title, children, onClose, onSubmit, valid }) {
  const [txId] = useState(newTxId);
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);
  const submit = async () => {
    setBusy(true);
    setError(null);
    try {
      await onSubmit(txId);
    } catch (err) {
      setError(err);
    } finally {
      setBusy(false);
    }
  };
  return (
    <Modal title={title} onClose={onClose}
           footer={(
             <>
               <button type="button" className="btn" onClick={onClose}>닫기</button>
               <button type="button" className="btn btn-primary" disabled={busy || !valid} onClick={submit}>{busy ? '처리 중…' : '개정 등록'}</button>
             </>
           )}>
      <p className="muted">시작일은 오늘 이후여야 하고, 직전 행은 시작일 전날로 자동 종료됩니다. 모든 변경은 요율 변경 이력에 남습니다.</p>
      {children}
      <ErrorBox error={error} />
    </Modal>
  );
}

function ClassFilter({ value, onChange }) {
  return (
    <div className="search-bar">
      <Field label="보험종목">
        <Select value={value} onChange={onChange} options={INSR_CLS} placeholder="전체" />
      </Field>
    </div>
  );
}

export function RatePage() {
  const { hasAny } = useAuth();
  const notify = useToast();
  const [insrClsCd, setInsrClsCd] = useState('');
  const [editing, setEditing] = useState(null);
  const [version, setVersion] = useState(0);
  const { data, error, loading } = useLoad(() => tariffApi.rateList({ insrClsCd: insrClsCd || null }), [insrClsCd, version]);

  return (
    <div className="page">
      <PageHeader title="담보 요율" desc="담보별 연 요율과 적용 기간입니다. 보험료는 보험시기에 적용 중인 요율로 산출합니다." />
      <Panel><ClassFilter value={insrClsCd} onChange={setInsrClsCd} /></Panel>
      <Panel>
        <ErrorBox error={error} />
        <DataTable loading={loading} rows={data?.items} rowKey={(r) => r.rateId} columns={[
          { key: 'insrClsCd', title: '종목', render: (r) => nameOf(INSR_CLS, r.insrClsCd) },
          { key: 'cvrgCd', title: '담보', render: (r) => <><span className="mono muted">{r.cvrgCd}</span> {r.cvrgNm}</> },
          { key: 'annualRate', title: '연 요율', align: 'right', render: (r) => `${(Number(r.annualRate) * 100).toFixed(4)}%` },
          { key: 'period', title: '적용 기간', render: period },
          { key: 'status', title: '상태', render: (r) => <EffStatus status={r.status} /> },
          {
            key: 'act', title: '', align: 'center', width: 80,
            render: (r) => hasAny('ADMIN') && r.status !== 'CLOSED' && (
              <button type="button" className="btn btn-sm" onClick={() => setEditing(r)}>개정</button>
            ),
          },
        ]} />
      </Panel>
      {editing && <RateReviseModal row={editing} onClose={() => setEditing(null)}
                                   onDone={() => { setEditing(null); notify('요율을 개정했습니다.'); setVersion((v) => v + 1); }} />}
    </div>
  );
}

function RateReviseModal({ row, onClose, onDone }) {
  const [form, setForm] = useState({ cvrgNm: row.cvrgNm, annualRate: String(row.annualRate), effFrom: seoulDate(1) });
  const rate = Number(form.annualRate);
  const valid = form.cvrgNm && rate > 0 && rate < 1 && form.effFrom;
  return (
    <ReviseModal title={`요율 개정 - ${row.cvrgCd} ${row.cvrgNm}`} onClose={onClose} valid={valid}
                 onSubmit={async (txId) => {
                   await tariffApi.rateRevise({ insrClsCd: row.insrClsCd, cvrgCd: row.cvrgCd, cvrgNm: form.cvrgNm, annualRate: rate, effFrom: form.effFrom }, txId);
                   onDone();
                 }}>
      <div className="form-grid">
        <Field label="담보명" required><input value={form.cvrgNm} onChange={(e) => setForm({ ...form, cvrgNm: e.target.value })} /></Field>
        <Field label="연 요율" required hint={`현재 ${row.annualRate} (= ${(Number(row.annualRate) * 100).toFixed(4)}%)`}>
          <input value={form.annualRate} inputMode="decimal" onChange={(e) => setForm({ ...form, annualRate: e.target.value })} />
        </Field>
        <Field label="적용 시작일" required><input type="date" value={form.effFrom} min={seoulDate()} onChange={(e) => setForm({ ...form, effFrom: e.target.value })} /></Field>
      </div>
    </ReviseModal>
  );
}

export function FactorPage() {
  const { hasAny } = useAuth();
  const notify = useToast();
  const [insrClsCd, setInsrClsCd] = useState('');
  const [editing, setEditing] = useState(null);
  const [version, setVersion] = useState(0);
  const { data, error, loading } = useLoad(() => tariffApi.factorList({ insrClsCd: insrClsCd || null }), [insrClsCd, version]);

  return (
    <div className="page">
      <PageHeader title="요율 계수" desc="운전자 나이, 할인할증 등급, 건물 구조 등급별로 기본 요율에 곱하는 계수입니다." />
      <Panel><ClassFilter value={insrClsCd} onChange={setInsrClsCd} /></Panel>
      <Panel>
        <ErrorBox error={error} />
        <DataTable loading={loading} rows={data?.items} rowKey={(r) => r.factorId} columns={[
          { key: 'insrClsCd', title: '종목', render: (r) => nameOf(INSR_CLS, r.insrClsCd) },
          { key: 'factorType', title: '계수 유형', render: (r) => nameOf(FACTOR_TYPES, r.factorType) },
          { key: 'key', title: '구간·등급', render: (r) => (r.factorKey ?? `${r.rangeFrom}~${r.rangeTo}세`) },
          { key: 'factorNm', title: '설명' },
          { key: 'factorValue', title: '계수', align: 'right', render: (r) => Number(r.factorValue).toFixed(4) },
          { key: 'period', title: '적용 기간', render: period },
          { key: 'status', title: '상태', render: (r) => <EffStatus status={r.status} /> },
          {
            key: 'act', title: '', align: 'center', width: 80,
            render: (r) => hasAny('ADMIN') && r.status !== 'CLOSED' && (
              <button type="button" className="btn btn-sm" onClick={() => setEditing(r)}>개정</button>
            ),
          },
        ]} />
      </Panel>
      {editing && <FactorReviseModal row={editing} onClose={() => setEditing(null)}
                                     onDone={() => { setEditing(null); notify('계수를 개정했습니다.'); setVersion((v) => v + 1); }} />}
    </div>
  );
}

function FactorReviseModal({ row, onClose, onDone }) {
  const [form, setForm] = useState({ factorNm: row.factorNm, factorValue: String(row.factorValue), effFrom: seoulDate(1) });
  const value = Number(form.factorValue);
  const valid = form.factorNm && value > 0 && form.effFrom;
  return (
    <ReviseModal title={`계수 개정 - ${row.factorNm}`} onClose={onClose} valid={valid}
                 onSubmit={async (txId) => {
                   await tariffApi.factorRevise({
                     insrClsCd: row.insrClsCd, factorType: row.factorType, factorKey: row.factorKey,
                     rangeFrom: row.rangeFrom, rangeTo: row.rangeTo, factorNm: form.factorNm, factorValue: value, effFrom: form.effFrom,
                   }, txId);
                   onDone();
                 }}>
      <div className="form-grid">
        <Field label="설명" required><input value={form.factorNm} onChange={(e) => setForm({ ...form, factorNm: e.target.value })} /></Field>
        <Field label="계수" required hint={`현재 ${Number(row.factorValue).toFixed(4)}`}>
          <input value={form.factorValue} inputMode="decimal" onChange={(e) => setForm({ ...form, factorValue: e.target.value })} />
        </Field>
        <Field label="적용 시작일" required><input type="date" value={form.effFrom} min={seoulDate()} onChange={(e) => setForm({ ...form, effFrom: e.target.value })} /></Field>
      </div>
    </ReviseModal>
  );
}

export function PolicyPage() {
  const { hasAny } = useAuth();
  const notify = useToast();
  const [editing, setEditing] = useState(null);
  const [version, setVersion] = useState(0);
  const { data, error, loading } = useLoad(() => tariffApi.policyList({}), [version]);

  return (
    <div className="page">
      <PageHeader title="필수 계수 정책" desc="보험료 산출과 청약 때 반드시 입력해야 하는 요율 계수입니다. 빠지면 E-PRM-005 로 거절됩니다." />
      <Panel>
        <ErrorBox error={error} />
        <DataTable loading={loading} rows={data?.items} rowKey={(r) => r.policyId} columns={[
          { key: 'insrClsCd', title: '종목', render: (r) => nameOf(INSR_CLS, r.insrClsCd) },
          { key: 'factorType', title: '계수 유형', render: (r) => nameOf(FACTOR_TYPES, r.factorType) },
          { key: 'required', title: '필수 여부', render: (r) => (r.required ? <Badge tone="info">필수</Badge> : <Badge>선택</Badge>) },
          { key: 'period', title: '적용 기간', render: period },
          { key: 'status', title: '상태', render: (r) => <EffStatus status={r.status} /> },
          {
            key: 'act', title: '', align: 'center', width: 80,
            render: (r) => hasAny('ADMIN') && r.status !== 'CLOSED' && (
              <button type="button" className="btn btn-sm" onClick={() => setEditing(r)}>개정</button>
            ),
          },
        ]} />
      </Panel>
      {editing && <PolicyReviseModal row={editing} onClose={() => setEditing(null)}
                                     onDone={() => { setEditing(null); notify('정책을 개정했습니다.'); setVersion((v) => v + 1); }} />}
    </div>
  );
}

function PolicyReviseModal({ row, onClose, onDone }) {
  const [required, setRequired] = useState(!row.required);
  const [effFrom, setEffFrom] = useState(seoulDate(1));
  return (
    <ReviseModal title={`정책 개정 - ${nameOf(FACTOR_TYPES, row.factorType)}`} onClose={onClose} valid={!!effFrom}
                 onSubmit={async (txId) => {
                   await tariffApi.policyRevise({ insrClsCd: row.insrClsCd, factorType: row.factorType, required, effFrom }, txId);
                   onDone();
                 }}>
      <div className="form-grid">
        <Field label="필수 여부" required>
          <Select value={required ? 'Y' : 'N'} onChange={(v) => setRequired(v === 'Y')} options={[{ code: 'Y', name: '필수' }, { code: 'N', name: '선택' }]} />
        </Field>
        <Field label="적용 시작일" required><input type="date" value={effFrom} min={seoulDate()} onChange={(e) => setEffFrom(e.target.value)} /></Field>
      </div>
    </ReviseModal>
  );
}

export function TariffAuditPage() {
  const [form, setForm] = useState({ targetType: '', targetKey: '', empId: '', fromDate: seoulDate(-30), toDate: seoulDate() });
  const [cond, setCond] = useState(form);
  const [page, setPage] = useState(0);
  const { data, error, loading } = useLoad(() => tariffApi.auditList({
    targetType: cond.targetType || null, targetKey: cond.targetKey.trim() || null, empId: cond.empId.trim() || null,
    fromDate: cond.fromDate || null, toDate: cond.toDate || null, page, size: 20,
  }), [cond, page]);

  return (
    <div className="page">
      <PageHeader title="요율 변경 이력" desc="요율·계수·필수 계수 정책의 모든 등록과 개정 기록입니다. 최근 순입니다." />
      <Panel>
        <form className="search-bar" onSubmit={(e) => { e.preventDefault(); setPage(0); setCond(form); }}>
          <Field label="대상"><Select value={form.targetType} onChange={(v) => setForm({ ...form, targetType: v })} options={AUDIT_TARGETS} placeholder="전체" /></Field>
          <Field label="대상 키 (앞부분)"><input value={form.targetKey} onChange={(e) => setForm({ ...form, targetKey: e.target.value })} placeholder="01:CA001" /></Field>
          <Field label="처리자"><input value={form.empId} onChange={(e) => setForm({ ...form, empId: e.target.value })} /></Field>
          <Field label="처리일">
            <div className="range">
              <input type="date" value={form.fromDate} onChange={(e) => setForm({ ...form, fromDate: e.target.value })} />
              <span>~</span>
              <input type="date" value={form.toDate} onChange={(e) => setForm({ ...form, toDate: e.target.value })} />
            </div>
          </Field>
          <div className="search-actions"><button type="submit" className="btn btn-primary">조회</button></div>
        </form>
      </Panel>
      <Panel>
        <ErrorBox error={error} />
        <DataTable loading={loading} rows={data?.items} columns={[
          { key: 'chgDt', title: '처리일시', render: (r) => dateTime(r.chgDt) },
          { key: 'targetType', title: '대상', render: (r) => nameOf(AUDIT_TARGETS, r.targetType) },
          { key: 'targetKey', title: '대상 키', render: (r) => <span className="mono">{r.targetKey}</span> },
          { key: 'action', title: '작업', render: (r) => (r.action === 'CREATE' ? '등록' : '개정') },
          { key: 'beforeVal', title: '변경 전', render: (r) => <span className="wrap">{r.beforeVal ?? '-'}</span> },
          { key: 'afterVal', title: '변경 후', render: (r) => <span className="wrap">{r.afterVal ?? '-'}</span> },
          { key: 'effFrom', title: '적용 시작', render: (r) => date(r.effFrom) },
          { key: 'empId', title: '처리자' },
        ]} />
        {data && <Pagination page={data.page} totalPages={data.totalPages} totalElements={data.totalElements} onChange={setPage} />}
      </Panel>
    </div>
  );
}

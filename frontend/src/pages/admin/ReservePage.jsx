import { useState } from 'react';
import { batchApi, statsApi } from '../../api/endpoints';
import { useAuth } from '../../auth/AuthProvider';
import { INSR_CLS, nameOf } from '../../common/codes';
import { dateTime, money } from '../../common/format';
import { Badge, DataTable, ErrorBox, Field, PageHeader, Panel, useLoad, useToast } from '../../components/ui';
import { Ratio } from './LossRatioPage';

// IBNR 산출(저장 없음)과 분기 지급준비금 결산(스프링 배치). 계산은 서버가 하고 화면은 근거(삼각형·계수)를 보여 준다.

function pct(v) {
  return v === null || v === undefined ? '-' : `${(Number(v) * 100).toFixed(1)}%`;
}

/** 직전 분기 말일 (결산 기본값) */
function lastQuarterEnd() {
  const now = new Date(Date.now() + 9 * 3600 * 1000);
  const y = now.getUTCFullYear();
  const q = Math.floor(now.getUTCMonth() / 3);
  const end = new Date(Date.UTC(y, q * 3, 0));
  return end.toISOString().slice(0, 10);
}

export function ReservePage() {
  const { hasAny } = useAuth();
  const notify = useToast();
  const [form, setForm] = useState({ insrClsCd: '01', periodType: 'QUARTER', periods: '12', evaluationDate: '', expectedLossRatio: '', tailFactor: '1' });
  const [cond, setCond] = useState(null);
  const ibnr = useLoad(() => (cond ? statsApi.ibnr(cond) : Promise.resolve(null)), [cond]);
  const [closeDate, setCloseDate] = useState(lastQuarterEnd());
  const [version, setVersion] = useState(0);
  const closes = useLoad(() => batchApi.reserveCloseList(null), [version]);
  const [closing, setClosing] = useState(false);
  const [closeError, setCloseError] = useState(null);

  const submit = (e) => {
    e.preventDefault();
    setCond({
      insrClsCd: form.insrClsCd,
      periodType: form.periodType,
      periods: Number(form.periods),
      evaluationDate: form.evaluationDate || null,
      expectedLossRatio: form.expectedLossRatio === '' ? null : Number(form.expectedLossRatio) / 100,
      tailFactor: form.tailFactor === '' ? null : Number(form.tailFactor),
    });
  };

  const runClose = async () => {
    setClosing(true);
    setCloseError(null);
    try {
      const res = await batchApi.reserveClose(closeDate);
      notify(res.status === 'COMPLETED' ? `${closeDate} 결산을 확정했습니다.` : `결산 배치 ${res.status}: ${res.exitMessage ?? ''}`,
        res.status === 'COMPLETED' ? 'ok' : 'danger');
      setVersion((v) => v + 1);
    } catch (err) {
      setCloseError(err);
    } finally {
      setClosing(false);
    }
  };

  const d = ibnr.data;
  const maxAge = d ? Math.max(...d.triangle.map((r) => r.cells.length)) : 0;

  return (
    <div className="page">
      <PageHeader title="IBNR·지급준비금"
                  desc="지급준비금 = 개별추산액(접수됐지만 아직 지급하지 않은 사고) + IBNR(이미 났지만 아직 접수되지 않았거나 덜 진전된 손해). IBNR 은 사고일·지급일 기준 진전 삼각형으로 추정합니다." />
      <Panel title="IBNR 산출 (저장하지 않음)">
        <form className="search-bar" onSubmit={submit}>
          <Field label="보험종목">
            <select value={form.insrClsCd} onChange={(e) => setForm({ ...form, insrClsCd: e.target.value })}>
              {INSR_CLS.map((c) => <option key={c.code} value={c.code}>{c.name}</option>)}
            </select>
          </Field>
          <Field label="사고 기간">
            <select value={form.periodType} onChange={(e) => setForm({ ...form, periodType: e.target.value, periods: e.target.value === 'YEAR' ? '5' : '12' })}>
              <option value="QUARTER">분기</option>
              <option value="YEAR">연</option>
            </select>
          </Field>
          <Field label="기간 수"><input type="number" min={2} max={40} value={form.periods} onChange={(e) => setForm({ ...form, periods: e.target.value })} /></Field>
          <Field label="평가 기준일" hint="비우면 직전 기간 말일"><input type="date" value={form.evaluationDate} onChange={(e) => setForm({ ...form, evaluationDate: e.target.value })} /></Field>
          <Field label="기대손해율 %" hint="비우면 실적(케이프코드)"><input type="number" min={1} max={300} value={form.expectedLossRatio} onChange={(e) => setForm({ ...form, expectedLossRatio: e.target.value })} /></Field>
          <Field label="꼬리 계수"><input type="number" step="0.01" min={1} max={3} value={form.tailFactor} onChange={(e) => setForm({ ...form, tailFactor: e.target.value })} /></Field>
          <div className="search-actions"><button type="submit" id="ibnr-run" className="btn btn-primary">산출</button></div>
        </form>
        <ErrorBox error={ibnr.error} />
        {ibnr.loading && <p className="muted">계산하는 중…</p>}
        {d && (
          <>
            <div className="kpi-row">
              <div className="kpi"><span className="kpi-label">개별추산액</span><strong>{money(d.total.caseReserve)}원</strong></div>
              <div className="kpi"><span className="kpi-label">IBNR (적립)</span><strong>{money(d.total.ibnr)}원</strong></div>
              <div className="kpi"><span className="kpi-label">지급준비금</span><strong>{money(d.total.reserve)}원</strong></div>
              <div className="kpi"><span className="kpi-label">기대손해율 {d.elrEstimated ? '(실적)' : '(입력)'}</span><strong>{pct(d.expectedLossRatio)}</strong></div>
            </div>
            {d.warnings.map((w) => <div key={w} className="alert alert-info small">{w}</div>)}

            <h3 className="sub-title">사고 기간별 결과 (기준일 {d.evaluationDate})</h3>
            <DataTable rowKey={(r) => r.period} rows={d.periods} columns={[
              { key: 'period', title: '사고 기간' },
              { key: 'age', title: '경과', align: 'right' },
              { key: 'earnedPremium', title: '경과보험료', align: 'right', render: (r) => money(r.earnedPremium) },
              { key: 'paid', title: '지급', align: 'right', render: (r) => money(r.paid) },
              { key: 'caseReserve', title: '개별추산', align: 'right', render: (r) => money(r.caseReserve) },
              { key: 'developed', title: '진전율', align: 'right', render: (r) => pct(r.developed) },
              { key: 'clIbnr', title: 'CL IBNR', align: 'right', render: (r) => money(r.clIbnr) },
              { key: 'bfIbnr', title: 'BF IBNR', align: 'right', render: (r) => (r.bfIbnr === null ? '-' : money(r.bfIbnr)) },
              { key: 'method', title: '선택', render: (r) => <Badge tone={r.method === 'CL' ? 'info' : 'warn'}>{r.method}</Badge> },
              { key: 'ibnr', title: '적립 IBNR', align: 'right', render: (r) => <strong>{money(r.ibnr)}</strong> },
              { key: 'ulr', title: '최종손해율', align: 'right', render: (r) => <Ratio value={r.ultimateLossRatio} /> },
            ]} />
            <p className="muted small">진전율 {d.matureThreshold * 100}% 이상은 체인래더(CL), 미만은 본휴터-퍼거슨(BF)을 고릅니다. 계산 IBNR 이 음수면 적립은 0 입니다.</p>

            <h3 className="sub-title">누적 지급 삼각형과 진전계수</h3>
            <div className="table-wrap">
              <table className="grid compact">
                <thead>
                  <tr><th>사고 기간</th>{Array.from({ length: maxAge }, (_, i) => <th key={i} style={{ textAlign: 'right' }}>경과 {i + 1}</th>)}</tr>
                </thead>
                <tbody>
                  {d.triangle.map((r) => (
                    <tr key={r.period}><td>{r.period}</td>
                      {Array.from({ length: maxAge }, (_, i) => <td key={i} className="num">{i < r.cells.length ? money(r.cells[i]) : ''}</td>)}
                    </tr>
                  ))}
                  <tr><td className="muted">진전계수</td>{d.factors.map((f) => <td key={f.age} className="num muted">{Number(f.factor).toFixed(3)}</td>)}</tr>
                  <tr><td className="muted">누적 계수</td>{d.factors.map((f) => <td key={f.age} className="num muted">{Number(f.cdf).toFixed(3)}</td>)}</tr>
                </tbody>
              </table>
            </div>
          </>
        )}
      </Panel>

      <Panel title="분기 지급준비금 결산 (스프링 배치)" actions={hasAny('ADMIN') && (
        <div className="inline-form">
          <input type="date" value={closeDate} onChange={(e) => setCloseDate(e.target.value)} />
          <button type="button" id="reserve-close" className="btn btn-primary" disabled={closing} onClick={runClose}>{closing ? '결산 중…' : '결산 실행'}</button>
        </div>
      )}>
        <p className="muted small">분기 말일 기준으로 보험종목마다 위와 같은 계산(분기 12개, 꼬리 1.0, 기대손해율은 실적)을 하고 확정합니다. 같은 기준일은 한 번만 결산하며, 확정한 값은 고치지 않고 다음 결산에서 정정합니다.</p>
        <ErrorBox error={closeError || closes.error} onClose={() => setCloseError(null)} />
        <DataTable rowKey={(r) => `${r.closeDt}-${r.insrClsCd}`} rows={closes.data} loading={closes.loading} empty="결산 내역이 없습니다." columns={[
          { key: 'closeDt', title: '기준일' },
          { key: 'cls', title: '보험종목', render: (r) => nameOf(INSR_CLS, r.insrClsCd) },
          { key: 'paidAmt', title: '지급', align: 'right', render: (r) => money(r.paidAmt) },
          { key: 'caseRsv', title: '개별추산', align: 'right', render: (r) => money(r.caseRsv) },
          { key: 'ibnrAmt', title: 'IBNR', align: 'right', render: (r) => money(r.ibnrAmt) },
          { key: 'totalRsv', title: '지급준비금', align: 'right', render: (r) => <strong>{money(r.totalRsv)}</strong> },
          { key: 'elr', title: '기대손해율', align: 'right', render: (r) => pct(r.elr) },
          { key: 'closeTm', title: '결산', render: (r) => `${dateTime(r.closeTm)} (${r.closeEmpId ?? '-'}, 실행 ${r.jobExecutionId})` },
        ]} />
      </Panel>
    </div>
  );
}

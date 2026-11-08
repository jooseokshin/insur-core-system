import { useRef, useState } from 'react';
import { statsApi } from '../../api/endpoints';
import { INSR_CLS } from '../../common/codes';
import { money, seoulDate } from '../../common/format';
import { ErrorBox, Field, PageHeader, Panel, useLoad } from '../../components/ui';
import { Ratio } from './LossRatioPage';

/** 변경률(%) 표시. 양수는 +를 붙인다. */
function Pct({ value }) {
  if (value === null || value === undefined) return <span className="muted">-</span>;
  const n = Number(value);
  return <span>{n > 0 ? '+' : ''}{n.toFixed(2)}%</span>;
}

/** 금액 증감 표시 */
function Delta({ value }) {
  const n = Number(value ?? 0);
  if (n === 0) return <span className="muted">0</span>;
  return <span>{n > 0 ? '+' : '−'}{money(Math.abs(n))}</span>;
}

const clampPct = (n) => Math.min(100, Math.max(-50, Math.round(n * 100) / 100));

/**
 * 요율 영향 시뮬레이션. 담보별 기본 요율 변경률을 넣고 돌리면 경험 손해율과 갱신 보험료가 어떻게 달라지는지 본다.
 * 아무것도 저장하지 않는다. 실제 개정은 '담보 요율' 화면에서 ADMIN 이 한다.
 */
export function RateImpactPage() {
  const [form, setForm] = useState({
    insrClsCd: '01', fromDate: seoulDate(-364), toDate: seoulDate(), targetPct: '', renewalWindowDays: '365',
  });
  const [changes, setChanges] = useState({});
  const [cond, setCond] = useState({ form, changes: {} });
  const { data, error, loading } = useLoad(() => statsApi.rateImpact({
    insrClsCd: cond.form.insrClsCd,
    fromDate: cond.form.fromDate || null,
    toDate: cond.form.toDate || null,
    targetLossRatio: cond.form.targetPct === '' ? null : Number(cond.form.targetPct) / 100,
    renewalWindowDays: cond.form.renewalWindowDays === '' ? null : Number(cond.form.renewalWindowDays),
    changes: Object.entries(cond.changes)
      .filter(([, v]) => v !== '' && Number(v) !== 0)
      .map(([cvrgCd, v]) => ({ cvrgCd, rateChangePct: Number(v) })),
  }), [cond]);

  // 오류가 나도 담보 입력 칸이 사라지지 않게 마지막 결과를 붙잡아 둔다
  const last = useRef(null);
  if (data) last.current = data;
  const shown = data ?? (last.current?.insrClsCd === cond.form.insrClsCd ? last.current : null);

  const run = (e) => {
    e?.preventDefault();
    setCond({ form, changes });
  };
  const changeClass = (insrClsCd) => {
    const next = { ...form, insrClsCd };
    setForm(next);
    setChanges({});
    setCond({ form: next, changes: {} });
  };
  const fillIndicated = () => {
    const next = {};
    shown.rows.forEach((r) => {
      if (r.credibilityWeightedChangePct !== null) next[r.cvrgCd] = String(clampPct(Number(r.credibilityWeightedChangePct)));
    });
    setChanges(next);
  };

  return (
    <div className="page">
      <PageHeader title="요율 영향 시뮬레이션"
                  desc="담보별 기본 요율을 바꾸면 경험 기간 손해율과 앞으로의 갱신 보험료가 어떻게 달라지는지 계산합니다. 저장하지 않으며 실제 개정은 '담보 요율' 화면에서 합니다." />
      <Panel>
        <form className="search-bar" onSubmit={run}>
          <Field label="보험종목">
            <select id="ri-cls" value={form.insrClsCd} onChange={(e) => changeClass(e.target.value)}>
              {INSR_CLS.map((c) => <option key={c.code} value={c.code}>{c.name}</option>)}
            </select>
          </Field>
          <Field label="경험 기간">
            <div className="range">
              <input id="ri-from" type="date" value={form.fromDate} onChange={(e) => setForm({ ...form, fromDate: e.target.value })} />
              <span>~</span>
              <input id="ri-to" type="date" value={form.toDate} onChange={(e) => setForm({ ...form, toDate: e.target.value })} />
            </div>
          </Field>
          <Field label="목표 손해율(%)">
            <input id="ri-target" className="num-input" inputMode="decimal" placeholder="예: 75" value={form.targetPct}
                   title="목표 손해율 = 1 − 사업비율 − 목표 이익률"
                   onChange={(e) => setForm({ ...form, targetPct: e.target.value })} />
          </Field>
          <Field label="갱신 영향 기간(일)">
            <input id="ri-window" className="num-input" inputMode="numeric" value={form.renewalWindowDays}
                   onChange={(e) => setForm({ ...form, renewalWindowDays: e.target.value })} />
          </Field>
          <div className="search-actions">
            <button type="submit" className="btn btn-primary">시뮬레이션</button>
          </div>
        </form>
        <ErrorBox error={error} />
        {loading && !shown && <p className="muted">계산하는 중…</p>}
        {shown && (
          <>
            <div className="form-actions" style={{ justifyContent: 'flex-start' }}>
              <button type="button" className="btn" onClick={fillIndicated} disabled={shown.targetLossRatio === null}>
                신뢰도 반영 변경률로 채우기
              </button>
              <button type="button" className="btn" onClick={() => setChanges({})}>변경률 비우기</button>
              <span className="muted small">
                갱신 영향: {shown.renewalFrom} ~ {shown.renewalTo} 만기 · 완전 신뢰 기준 사고 {shown.fullCredibilityClaims.toLocaleString()}건
              </span>
            </div>
            <div className="table-wrap">
              <table className="grid">
                <thead>
                  <tr>
                    <th>담보</th>
                    <th style={{ textAlign: 'right' }}>변경률(%)</th>
                    <th style={{ textAlign: 'right' }}>연 요율 (현재 → 새)</th>
                    <th style={{ textAlign: 'right' }}>경과보험료</th>
                    <th style={{ textAlign: 'right' }}>발생손해액</th>
                    <th style={{ textAlign: 'right' }}>사고</th>
                    <th style={{ textAlign: 'right' }}>손해율</th>
                    <th style={{ textAlign: 'right' }}>새 요율 손해율</th>
                    <th style={{ textAlign: 'right' }}>필요 변경률</th>
                    <th style={{ textAlign: 'right' }}>신뢰도</th>
                    <th style={{ textAlign: 'right' }}>신뢰도 반영</th>
                    <th style={{ textAlign: 'right' }}>갱신 대상</th>
                    <th style={{ textAlign: 'right' }}>갱신 보험료 증감</th>
                  </tr>
                </thead>
                <tbody>
                  {shown.rows.map((r) => (
                    <tr key={r.cvrgCd}>
                      <td>{r.cvrgNm} ({r.cvrgCd})</td>
                      <td className="num">
                        <input className="num-input" style={{ width: 80 }} inputMode="decimal" aria-label={`${r.cvrgNm} 변경률`}
                               value={changes[r.cvrgCd] ?? ''} placeholder="0"
                               onChange={(e) => setChanges({ ...changes, [r.cvrgCd]: e.target.value })} />
                      </td>
                      <td className="num">{Number(r.currentRate)} → {Number(r.newRate)}</td>
                      <td className="num">{money(r.earnedPremium)}</td>
                      <td className="num">{money(r.incurredLoss)}</td>
                      <td className="num">{r.claimCount.toLocaleString()}</td>
                      <td className="num"><Ratio value={r.lossRatio} /></td>
                      <td className="num"><Ratio value={r.simulatedLossRatio} /></td>
                      <td className="num"><Pct value={r.indicatedChangePct} /></td>
                      <td className="num">{Number(r.credibility).toFixed(2)}</td>
                      <td className="num"><Pct value={r.credibilityWeightedChangePct} /></td>
                      <td className="num">{r.renewalContracts.toLocaleString()}건</td>
                      <td className="num"><Delta value={r.renewalPremiumChange} /></td>
                    </tr>
                  ))}
                </tbody>
                <tfoot>
                  <tr>
                    <td>전체</td>
                    <td className="num"><Pct value={shown.total.rateChangePct} /></td>
                    <td />
                    <td className="num">{money(shown.total.earnedPremium)}</td>
                    <td className="num">{money(shown.total.incurredLoss)}</td>
                    <td className="num">{shown.total.claimCount.toLocaleString()}</td>
                    <td className="num"><Ratio value={shown.total.lossRatio} /></td>
                    <td className="num"><Ratio value={shown.total.simulatedLossRatio} /></td>
                    <td className="num"><Pct value={shown.total.indicatedChangePct} /></td>
                    <td className="num">{Number(shown.total.credibility).toFixed(2)}</td>
                    <td className="num"><Pct value={shown.total.credibilityWeightedChangePct} /></td>
                    <td className="num">{shown.total.renewalContracts.toLocaleString()}건</td>
                    <td className="num"><Delta value={shown.total.renewalPremiumChange} /></td>
                  </tr>
                </tfoot>
              </table>
            </div>
            {shown.warnings.length > 0 && (
              <div className="alert alert-info">
                <ul style={{ margin: 0, paddingLeft: 18 }}>
                  {shown.warnings.map((w) => <li key={w}>{w}</li>)}
                </ul>
              </div>
            )}
          </>
        )}
        <p className="muted small">
          새 요율 손해율은 같은 계약과 사고에 보험료만 변경률만큼 달랐다고 본 값입니다(보험료는 기본 요율에 정비례). 필요 변경률은 손해율 ÷ 목표 손해율 − 1(손해율법)이고,
          신뢰도 Z = √(사고 건수 ÷ 1,082)만큼만 반영하고 나머지는 현재 요율 유지로 봅니다. 1,082건은 사고 건수가 90% 확률로 ±5% 안에 드는 기준입니다(제한변동 신뢰도).
          목표 손해율은 1 − 사업비율 − 목표 이익률로 정합니다. 손해 추세, 미보고 손해(IBNR), 사업비, 요율 변경에 따른 해약·신계약 변화는 반영하지 않으므로 계리 검토를 대신하지 않습니다.
          전체 행의 변경률은 경과보험료 가중 평균입니다.
        </p>
      </Panel>
    </div>
  );
}

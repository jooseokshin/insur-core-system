import { useState } from 'react';
import { statsApi } from '../../api/endpoints';
import { INSR_CLS, coverageName, nameOf } from '../../common/codes';
import { money, seoulDate } from '../../common/format';
import { Badge, ErrorBox, Field, PageHeader, Panel, useLoad } from '../../components/ui';

const GROUPS = [
  { code: 'PRODUCT', name: '상품별' },
  { code: 'COVERAGE', name: '담보별' },
  { code: 'CLASS', name: '보험종목별' },
];

/** 손해율 표시: 100% 이상 위험, 80% 이상 주의 (판단 기준은 화면 표시용 임의값) */
function Ratio({ value }) {
  if (value === null || value === undefined) return <span className="muted">-</span>;
  const pct = Number(value) * 100;
  const tone = pct >= 100 ? 'danger' : pct >= 80 ? 'warn' : 'ok';
  return <Badge tone={tone}>{pct.toFixed(1)}%</Badge>;
}

export function LossRatioPage() {
  const [form, setForm] = useState({ fromDate: `${seoulDate().slice(0, 4)}-01-01`, toDate: seoulDate() });
  const [cond, setCond] = useState(form);
  const [groupBy, setGroupBy] = useState('PRODUCT');
  const { data, error, loading } = useLoad(() => statsApi.lossRatio({ ...cond, groupBy }), [cond, groupBy]);

  const label = (r) => {
    if (groupBy === 'PRODUCT') return r.prdCd ?? '상품 없음 (기존 채널)';
    if (groupBy === 'COVERAGE') return `${coverageName(r.cvrgCd)} (${r.cvrgCd})`;
    return '-';
  };

  return (
    <div className="page">
      <PageHeader title="손해율 분석"
                  desc="손해율 = 발생손해액 ÷ 경과보험료. 사고일이 기간 안인 사고의 지급액과 미지급 추산액을, 기간 안에 보장한 만큼의 보험료로 나눕니다." />
      <Panel>
        <form className="search-bar" onSubmit={(e) => { e.preventDefault(); setCond(form); }}>
          <Field label="집계 기간">
            <div className="range">
              <input id="lr-from" type="date" value={form.fromDate} onChange={(e) => setForm({ ...form, fromDate: e.target.value })} />
              <span>~</span>
              <input id="lr-to" type="date" value={form.toDate} onChange={(e) => setForm({ ...form, toDate: e.target.value })} />
            </div>
          </Field>
          <div className="search-actions"><button type="submit" className="btn btn-primary">조회</button></div>
        </form>
        <div className="tabs" role="tablist">
          {GROUPS.map((g) => (
            <button key={g.code} type="button" role="tab" aria-selected={groupBy === g.code}
                    className={`tab ${groupBy === g.code ? 'active' : ''}`} onClick={() => setGroupBy(g.code)}>{g.name}</button>
          ))}
        </div>
        <ErrorBox error={error} />
        {loading && !data && <p className="muted">집계하는 중…</p>}
        {data && (
          <div className="table-wrap">
            <table className="grid">
              <thead>
                <tr>
                  <th>보험종목</th>
                  {groupBy !== 'CLASS' && <th>{groupBy === 'PRODUCT' ? '상품' : '담보'}</th>}
                  <th style={{ textAlign: 'right' }}>계약 수</th>
                  <th style={{ textAlign: 'right' }}>경과보험료</th>
                  <th style={{ textAlign: 'right' }}>사고 건수</th>
                  <th style={{ textAlign: 'right' }}>지급 보험금</th>
                  <th style={{ textAlign: 'right' }}>미지급 추산</th>
                  <th style={{ textAlign: 'right' }}>발생손해액</th>
                  <th style={{ textAlign: 'right' }}>손해율</th>
                </tr>
              </thead>
              <tbody>
                {data.rows.length === 0 && (
                  <tr><td colSpan={9} className="grid-empty">기간 안에 보장한 계약이나 사고가 없습니다.</td></tr>
                )}
                {data.rows.map((r) => (
                  <tr key={`${r.insrClsCd}|${r.prdCd}|${r.cvrgCd}`}>
                    <td>{nameOf(INSR_CLS, r.insrClsCd)}</td>
                    {groupBy !== 'CLASS' && <td>{label(r)}</td>}
                    <td className="num">{r.contractCount.toLocaleString()}</td>
                    <td className="num">{money(r.earnedPremium)}</td>
                    <td className="num">{r.claimCount.toLocaleString()}</td>
                    <td className="num">{money(r.paidLoss)}</td>
                    <td className="num">{money(r.outstandingLoss)}</td>
                    <td className="num">{money(r.incurredLoss)}</td>
                    <td className="num"><Ratio value={r.lossRatio} /></td>
                  </tr>
                ))}
              </tbody>
              <tfoot>
                <tr>
                  <td colSpan={groupBy !== 'CLASS' ? 2 : 1}>전체</td>
                  <td className="num">{data.total.contractCount.toLocaleString()}</td>
                  <td className="num">{money(data.total.earnedPremium)}</td>
                  <td className="num">{data.total.claimCount.toLocaleString()}</td>
                  <td className="num">{money(data.total.paidLoss)}</td>
                  <td className="num">{money(data.total.outstandingLoss)}</td>
                  <td className="num">{money(data.total.incurredLoss)}</td>
                  <td className="num"><Ratio value={data.total.lossRatio} /></td>
                </tr>
              </tfoot>
            </table>
          </div>
        )}
        <p className="muted small">
          경과보험료는 보험시기부터 보험종기·해지일 중 이른 날 전날까지를 보장 기간으로 봅니다. 미지급 추산은 지급 대기 건의 사정 지급액과 접수 건의 청구액입니다.
          아직 보고되지 않은 손해(IBNR)는 넣지 않아서 최근 기간일수록 손해율이 실제보다 낮게 나옵니다. 담보별 계약 수를 더하면 한 계약이 여러 담보에 잡혀 전체보다 커집니다.
        </p>
      </Panel>
    </div>
  );
}

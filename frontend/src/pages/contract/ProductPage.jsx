import { useState } from 'react';
import { productApi } from '../../api/endpoints';
import { CVRG_TYPES, INSR_CLS, nameOf } from '../../common/codes';
import { date, money } from '../../common/format';
import { Badge, DataTable, ErrorBox, Field, PageHeader, Panel, Select, useLoad } from '../../components/ui';

/** 상품 조회: 상품별 판매 기간과 주계약·특약 담보 구성, 가입 한도, 자기부담금 */
export function ProductPage() {
  const [insrClsCd, setInsrClsCd] = useState('');
  const [onSaleOnly, setOnSaleOnly] = useState(false);
  const { data, error, loading } = useLoad(() => productApi.list({ insrClsCd: insrClsCd || null, onSaleOnly }), [insrClsCd, onSaleOnly]);

  return (
    <div className="page">
      <PageHeader title="상품 조회" desc="상품마다 반드시 가입하는 주계약과 골라서 더하는 특약이 있고, 담보별 가입금액 한도와 자기부담금이 정해져 있습니다." />
      <Panel>
        <div className="search-bar">
          <Field label="보험종목"><Select value={insrClsCd} onChange={setInsrClsCd} options={INSR_CLS} placeholder="전체" /></Field>
          <label className="check"><input type="checkbox" checked={onSaleOnly} onChange={(e) => setOnSaleOnly(e.target.checked)} /> 판매 중인 상품만</label>
        </div>
      </Panel>
      <ErrorBox error={error} />
      {loading && !data && <p className="muted">불러오는 중…</p>}
      {data?.items.map((p) => (
        <Panel key={p.prdCd} title={<>{p.prdNm} <span className="mono muted small">{p.prdCd}</span></>}
               actions={p.onSale ? <Badge tone="ok">판매 중</Badge> : <Badge>판매 종료</Badge>}>
          <p className="muted small">
            {nameOf(INSR_CLS, p.insrClsCd)} · 판매 {date(p.saleFrom)} ~ {p.saleTo ? date(p.saleTo) : ''}
            {p.prdDesc ? ` · ${p.prdDesc}` : ''}
          </p>
          <DataTable rows={p.coverages} rowKey={(r) => r.cvrgCd} columns={[
            { key: 'cvrgType', title: '구분', render: (r) => <Badge tone={CVRG_TYPES.find((t) => t.code === r.cvrgType)?.tone}>{nameOf(CVRG_TYPES, r.cvrgType)}</Badge> },
            { key: 'cvrgCd', title: '담보', render: (r) => <><span className="mono muted">{r.cvrgCd}</span> {r.cvrgNm ?? '-'}</> },
            { key: 'required', title: '가입', render: (r) => (r.required ? '필수' : '선택') },
            { key: 'limit', title: '가입 한도', align: 'right', render: (r) => `${money(r.minIsrdAmt)} ~ ${money(r.maxIsrdAmt)}` },
            { key: 'deductibleAmt', title: '자기부담금', align: 'right', render: (r) => (Number(r.deductibleAmt) > 0 ? money(r.deductibleAmt) : '-') },
          ]} />
        </Panel>
      ))}
      {data && data.items.length === 0 && <Panel><p className="muted">조건에 맞는 상품이 없습니다.</p></Panel>}
    </div>
  );
}

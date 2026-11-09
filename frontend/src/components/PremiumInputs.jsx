import { useEffect } from 'react';
import { productApi } from '../api/endpoints';
import { CVRG_TYPES, INSR_CLS, RATING_FIELDS, nameOf } from '../common/codes';
import { addDays, addYears, daysBetween, money, parseAmount, seoulDate } from '../common/format';
import { Badge, Field, Select, useLoad } from './ui';

// 보험료 입력: 상품을 고르면 그 상품의 주계약(필수)·특약(선택) 담보와 가입금액 한도가 입력 표가 된다.
// 서버도 같은 규칙(E-PRD-004~006)으로 다시 검사하므로, 여기 검증은 입력을 돕는 용도다.

/** 판매 중인 상품 목록 (보험종목별) */
export function useProducts() {
  return useLoad(() => productApi.list({ onSaleOnly: true }), []);
}

/** 종목별 요율 계수 입력의 시작값. 중립 계수(1.0)가 있으면 그 값, 고객마다 다른 값(건물 구조, 여행지, 나이)은 빈칸 */
function neutralRating(insrClsCd) {
  switch (insrClsCd) {
    case '01': return { driverAge: '35', discountGrade: '3' };
    case '03': return { driverAge: '40' };
    case '04': return { travelRegion: '', insuredAge: '' };
    default: return { structureGrade: '' };
  }
}

/** 보험시기에서 상품의 기본 종료일: 1년 미만 상품(해외여행 1~90일)은 7일(상한이 더 짧으면 상한), 그 밖은 1년 */
export function defaultEndDate(startDate, product) {
  const max = product?.maxTermDays;
  if (max && max < 365) return addDays(startDate, Math.min(7, max));
  return addYears(startDate, 1);
}

/** 상품을 고른 직후의 가입금액: 필수 담보는 최소 한도, 특약은 미가입 */
function defaultAmounts(product) {
  return Object.fromEntries((product?.coverages ?? []).map((c) => [c.cvrgCd, c.required ? money(c.minIsrdAmt) : '']));
}

/** 보험료 입력값의 기본 상태: 내일 시작 1년 계약, 요율 계수는 중립값(만 35세, 3등급) */
export function initialPremiumInput(insrClsCd = '01', product = null) {
  const start = seoulDate(1);
  return {
    insrClsCd,
    prdCd: product?.prdCd ?? '',
    startDate: start,
    endDate: defaultEndDate(start, product),
    rating: neutralRating(insrClsCd),
    amounts: defaultAmounts(product),
  };
}

/** 입력값을 산출 API 요청으로 바꾼다. 가입금액을 넣은 담보만 보낸다. 오류가 있으면 문자열로 돌려준다. */
export function toQuoteRequest(input, product) {
  if (!product) return { error: '상품을 선택해 주세요.' };
  const coverages = [];
  for (const c of product.coverages) {
    const amount = parseAmount(input.amounts[c.cvrgCd]);
    const label = c.cvrgNm ?? c.cvrgCd;
    if (amount === null) {
      if (c.required) return { error: `${label}은(는) 필수 담보입니다. 가입금액을 입력해 주세요.` };
      continue;
    }
    if (Number.isNaN(amount) || amount < Number(c.minIsrdAmt) || amount > Number(c.maxIsrdAmt)) {
      return { error: `${label}의 가입금액은 ${money(c.minIsrdAmt)}~${money(c.maxIsrdAmt)}원이어야 합니다.` };
    }
    coverages.push({ cvrgCd: c.cvrgCd, isrdAmt: amount });
  }
  const days = daysBetween(input.startDate, input.endDate);
  if (!days || days <= 0) return { error: '보험종기는 보험시기 이후여야 합니다.' };
  const rating = {};
  for (const f of RATING_FIELDS[input.insrClsCd]) {
    const v = input.rating[f.key];
    if (v !== '' && v !== undefined && v !== null) rating[f.key] = f.type === 'number' ? Number(v) : v;
  }
  return {
    request: {
      insrClsCd: input.insrClsCd,
      prdCd: input.prdCd,
      startDate: input.startDate,
      endDate: input.endDate,
      rating: Object.keys(rating).length ? rating : null,
      coverages,
    },
  };
}

/** 보험종목·상품, 보험기간, 요율 계수, 담보별 가입금액 입력 */
export function PremiumInputs({ value, onChange, products, disabled }) {
  const set = (patch) => onChange({ ...value, ...patch });
  const days = daysBetween(value.startDate, value.endDate);
  const ofClass = (products ?? []).filter((p) => p.insrClsCd === value.insrClsCd);
  const product = ofClass.find((p) => p.prdCd === value.prdCd);

  // 상품 목록이 늦게 오거나 종목을 바꾸면 그 종목의 첫 상품을 고른다
  useEffect(() => {
    if (!product && ofClass.length > 0) {
      onChange({ ...value, prdCd: ofClass[0].prdCd, amounts: defaultAmounts(ofClass[0]),
        endDate: defaultEndDate(value.startDate, ofClass[0]) });
    }
  }, [product, ofClass, value, onChange]);

  const chooseClass = (code) => {
    const first = (products ?? []).find((p) => p.insrClsCd === code);
    onChange({ ...initialPremiumInput(code, first), startDate: value.startDate, endDate: defaultEndDate(value.startDate, first) });
  };
  const chooseProduct = (prdCd) => {
    const next = ofClass.find((p) => p.prdCd === prdCd);
    set({ prdCd, amounts: defaultAmounts(next), endDate: defaultEndDate(value.startDate, next) });
  };

  return (
    <div className="premium-inputs">
      <div className="form-grid">
        <Field label="보험종목" required>
          <Select id="insr-cls" value={value.insrClsCd} disabled={disabled} onChange={chooseClass} options={INSR_CLS} />
        </Field>
        <Field label="상품" required wide>
          <Select id="product" value={value.prdCd} disabled={disabled || ofClass.length === 0} onChange={chooseProduct}
                  options={ofClass.map((p) => ({ code: p.prdCd, name: `${p.prdNm} (${p.prdCd})` }))}
                  placeholder={ofClass.length === 0 ? '판매 중인 상품이 없습니다' : undefined} />
        </Field>
        <Field label="보험시기" required>
          <input id="start-date" type="date" value={value.startDate} disabled={disabled}
                 onChange={(e) => set({ startDate: e.target.value, endDate: e.target.value ? addYears(e.target.value, 1) : value.endDate })} />
        </Field>
        <Field label="보험종기" required hint={days > 0 ? `보험기간 ${days}일` : '보험시기 이후여야 합니다'}>
          <input id="end-date" type="date" value={value.endDate} disabled={disabled} onChange={(e) => set({ endDate: e.target.value })} />
        </Field>
        {RATING_FIELDS[value.insrClsCd].map((f) => (
          <Field key={f.key} label={f.label} required>
            {f.type === 'select' ? (
              <Select id={`rating-${f.key}`} value={value.rating[f.key]} disabled={disabled} options={f.options} placeholder="선택"
                      onChange={(v) => set({ rating: { ...value.rating, [f.key]: v } })} />
            ) : (
              <input id={`rating-${f.key}`} type="number" min={f.min ?? 0} max={f.max ?? 150} value={value.rating[f.key] ?? ''} placeholder={f.placeholder}
                     disabled={disabled} onChange={(e) => set({ rating: { ...value.rating, [f.key]: e.target.value } })} />
            )}
          </Field>
        ))}
      </div>
      {product?.prdDesc && <p className="muted small">{product.prdDesc}</p>}
      <div className="table-wrap">
      <table className="grid compact coverage-input">
        <thead>
          <tr>
            <th>구분</th>
            <th>담보</th>
            <th style={{ textAlign: 'right' }}>가입 한도 (원)</th>
            <th style={{ textAlign: 'right' }}>자기부담금</th>
            <th style={{ textAlign: 'right' }}>가입금액 (원)</th>
          </tr>
        </thead>
        <tbody>
          {(product?.coverages ?? []).map((c) => (
            <tr key={c.cvrgCd}>
              <td>
                <Badge tone={CVRG_TYPES.find((t) => t.code === c.cvrgType)?.tone}>{nameOf(CVRG_TYPES, c.cvrgType)}</Badge>
              </td>
              <td>
                <span className="mono muted">{c.cvrgCd}</span> {c.cvrgNm}
                {c.required && <em className="req">*</em>}
              </td>
              <td className="num muted small">{money(c.minIsrdAmt)} ~ {money(c.maxIsrdAmt)}</td>
              <td className="num muted small">{Number(c.deductibleAmt) > 0 ? money(c.deductibleAmt) : '-'}</td>
              <td style={{ textAlign: 'right' }}>
                <input id={`amt-${c.cvrgCd}`} className="num-input" inputMode="numeric" placeholder={c.required ? '필수' : '미가입'}
                       disabled={disabled} value={value.amounts[c.cvrgCd] ?? ''}
                       onChange={(e) => set({ amounts: { ...value.amounts, [c.cvrgCd]: e.target.value } })}
                       onBlur={(e) => {
                         const n = parseAmount(e.target.value);
                         if (n && !Number.isNaN(n)) set({ amounts: { ...value.amounts, [c.cvrgCd]: money(n) } });
                       }} />
              </td>
            </tr>
          ))}
          {!product && (
            <tr>
              <td colSpan={5} className="grid-empty">상품을 불러오는 중이거나 선택할 상품이 없습니다.</td>
            </tr>
          )}
        </tbody>
      </table>
      </div>
    </div>
  );
}

/** 산출 결과: 적용 계수와 담보별 보험료 */
export function QuoteResult({ quote }) {
  if (!quote) return null;
  return (
    <div className="quote-result">
      <div className="quote-total">
        <span>총보험료</span>
        <strong id="quote-total">{money(quote.totalPremium)}원</strong>
        <small>보험기간 {quote.days}일 · 계수 합 {Number(quote.combinedFactor).toFixed(4)}</small>
      </div>
      {quote.factors?.length > 0 && (
        <ul className="factor-chips">
          {quote.factors.map((f) => (
            <li key={f.factorType}>
              {f.factorNm} <b>×{Number(f.factorValue).toFixed(4)}</b>
            </li>
          ))}
        </ul>
      )}
      <table className="grid compact">
        <thead>
          <tr>
            <th>담보</th>
            <th style={{ textAlign: 'right' }}>가입금액</th>
            <th style={{ textAlign: 'right' }}>연 요율</th>
            <th style={{ textAlign: 'right' }}>보험료</th>
          </tr>
        </thead>
        <tbody>
          {quote.coverages.map((c) => (
            <tr key={c.cvrgCd}>
              <td>{c.cvrgNm ?? c.cvrgCd}</td>
              <td className="num">{money(c.isrdAmt)}</td>
              <td className="num">{(Number(c.annualRate) * 100).toFixed(4)}%</td>
              <td className="num">{money(c.prem)}</td>
            </tr>
          ))}
        </tbody>
      </table>
      <p className="muted small">보험료 = 가입금액 × 연 요율 × 요율 계수 × 보험기간 일수 ÷ 365 (원 단위 반올림)</p>
    </div>
  );
}

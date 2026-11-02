import { COVERAGES, INSR_CLS, RATING_FIELDS } from '../common/codes';
import { addYears, daysBetween, money, parseAmount, seoulDate } from '../common/format';
import { Field, Select } from './ui';

/** 보험료 입력값의 기본 상태: 내일 시작 1년 계약, 요율 계수는 중립값(만 35세, 3등급) */
export function initialPremiumInput(insrClsCd = '01') {
  const start = seoulDate(1);
  return {
    insrClsCd,
    startDate: start,
    endDate: addYears(start, 1),
    rating: insrClsCd === '01' ? { driverAge: '35', discountGrade: '3' } : { structureGrade: '' },
    amounts: Object.fromEntries(COVERAGES[insrClsCd].map((c, i) => [c.code, i === 0 ? '100,000,000' : ''])),
  };
}

/** 입력값을 산출 API 요청으로 바꾼다. 가입금액을 넣은 담보만 보낸다. 오류가 있으면 문자열로 돌려준다. */
export function toQuoteRequest(input) {
  const coverages = [];
  for (const c of COVERAGES[input.insrClsCd]) {
    const amount = parseAmount(input.amounts[c.code]);
    if (amount === null) continue;
    if (Number.isNaN(amount) || amount <= 0) return { error: `${c.name}의 가입금액은 0보다 큰 숫자여야 합니다.` };
    coverages.push({ cvrgCd: c.code, isrdAmt: amount });
  }
  if (coverages.length === 0) return { error: '가입할 담보의 가입금액을 하나 이상 입력해 주세요.' };
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
      startDate: input.startDate,
      endDate: input.endDate,
      rating: Object.keys(rating).length ? rating : null,
      coverages,
    },
  };
}

/** 보험종목, 보험기간, 요율 계수, 담보별 가입금액 입력 */
export function PremiumInputs({ value, onChange, disabled }) {
  const set = (patch) => onChange({ ...value, ...patch });
  const days = daysBetween(value.startDate, value.endDate);

  return (
    <div className="premium-inputs">
      <div className="form-grid">
        <Field label="보험종목" required>
          <Select id="insr-cls" value={value.insrClsCd} disabled={disabled}
                  onChange={(code) => onChange({ ...initialPremiumInput(code), startDate: value.startDate, endDate: value.endDate })}
                  options={INSR_CLS} />
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
              <input id={`rating-${f.key}`} type="number" min="18" max="99" value={value.rating[f.key] ?? ''} placeholder={f.placeholder}
                     disabled={disabled} onChange={(e) => set({ rating: { ...value.rating, [f.key]: e.target.value } })} />
            )}
          </Field>
        ))}
      </div>
      <table className="grid compact coverage-input">
        <thead>
          <tr>
            <th>담보</th>
            <th style={{ textAlign: 'right' }}>가입금액 (원)</th>
          </tr>
        </thead>
        <tbody>
          {COVERAGES[value.insrClsCd].map((c) => (
            <tr key={c.code}>
              <td>
                <span className="mono muted">{c.code}</span> {c.name}
              </td>
              <td style={{ textAlign: 'right' }}>
                <input id={`amt-${c.code}`} className="num-input" inputMode="numeric" placeholder="미가입" disabled={disabled}
                       value={value.amounts[c.code] ?? ''}
                       onChange={(e) => set({ amounts: { ...value.amounts, [c.code]: e.target.value } })}
                       onBlur={(e) => {
                         const n = parseAmount(e.target.value);
                         if (n && !Number.isNaN(n)) set({ amounts: { ...value.amounts, [c.code]: money(n) } });
                       }} />
              </td>
            </tr>
          ))}
        </tbody>
      </table>
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

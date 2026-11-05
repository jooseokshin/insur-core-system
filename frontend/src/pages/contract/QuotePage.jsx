import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { premiumApi } from '../../api/endpoints';
import { useAuth } from '../../auth/AuthProvider';
import { ErrorBox, PageHeader, Panel } from '../../components/ui';
import { PremiumInputs, QuoteResult, initialPremiumInput, toQuoteRequest, useProducts } from '../../components/PremiumInputs';

export function QuotePage() {
  const navigate = useNavigate();
  const { hasAny } = useAuth();
  const products = useProducts();
  const [input, setInput] = useState(() => initialPremiumInput());
  const [quote, setQuote] = useState(null);
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);

  const run = async () => {
    const { request, error: invalid } = toQuoteRequest(input, products.data?.items.find((p) => p.prdCd === input.prdCd));
    if (invalid) {
      setError({ message: invalid });
      return;
    }
    setBusy(true);
    setError(null);
    try {
      setQuote(await premiumApi.quote(request));
    } catch (err) {
      setError(err);
      setQuote(null);
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="page">
      <PageHeader title="보험료 산출" desc="가입 조건을 넣으면 요율표 기준 보험료를 계산합니다. 저장되지 않습니다." />
      <div className="two-col wide-left">
        <Panel title="가입 조건">
          <ErrorBox error={products.error} />
          <PremiumInputs value={input} onChange={(v) => { setInput(v); setQuote(null); }} products={products.data?.items} />
          <ErrorBox error={error} />
          <div className="form-actions">
            <button type="button" className="btn" onClick={() => { setInput(initialPremiumInput(input.insrClsCd)); setQuote(null); }}>초기화</button>
            <button type="button" className="btn btn-primary" onClick={run} disabled={busy}>{busy ? '산출 중…' : '보험료 산출'}</button>
          </div>
        </Panel>
        <Panel title="산출 결과">
          {quote ? <QuoteResult quote={quote} /> : <p className="muted">조건을 입력하고 보험료 산출을 눌러 주세요.</p>}
          {quote && hasAny('AGENT', 'ADMIN') && (
            <div className="form-actions">
              <button type="button" className="btn btn-primary" onClick={() => navigate('/contracts/apply', { state: { input } })}>
                이 조건으로 청약 입력 →
              </button>
            </div>
          )}
        </Panel>
      </div>
    </div>
  );
}

import { useState } from 'react';
import { Link, useLocation } from 'react-router-dom';
import { contractApi, premiumApi } from '../../api/endpoints';
import { newTxId } from '../../api/http';
import { money } from '../../common/format';
import { ErrorBox, Field, PageHeader, Panel, useToast } from '../../components/ui';
import { PremiumInputs, QuoteResult, initialPremiumInput, toQuoteRequest, useProducts } from '../../components/PremiumInputs';

/**
 * 청약 입력. 보험료는 반드시 산출 API 결과를 그대로 보낸다(서버가 요율표로 다시 계산해 다르면 E-CTR-020 으로 거절).
 * 거래 UUID 는 화면을 열 때 한 번 만들고, 응답을 못 받아 다시 누르면 같은 값으로 보내 중복 접수를 막는다.
 */
export function ApplyPage() {
  const location = useLocation();
  const notify = useToast();
  const products = useProducts();
  const [input, setInput] = useState(() => location.state?.input ?? initialPremiumInput());
  const [policyholderId, setPolicyholderId] = useState('');
  const [insuredId, setInsuredId] = useState('');
  const [sameAsHolder, setSameAsHolder] = useState(true);
  const [quote, setQuote] = useState(null);
  const [quotedRequest, setQuotedRequest] = useState(null);
  const [txId, setTxId] = useState(newTxId);
  const [result, setResult] = useState(null);
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);

  const changeInput = (v) => {
    setInput(v);
    setQuote(null);   // 조건이 바뀌면 산출을 다시 해야 한다
  };

  const runQuote = async () => {
    const { request, error: invalid } = toQuoteRequest(input, products.data?.items.find((p) => p.prdCd === input.prdCd));
    if (invalid) return setError({ message: invalid });
    setBusy(true);
    setError(null);
    try {
      setQuote(await premiumApi.quote(request));
      setQuotedRequest(request);
    } catch (err) {
      setError(err);
    } finally {
      setBusy(false);
    }
  };

  const submit = async () => {
    const holder = policyholderId.trim();
    const insured = sameAsHolder ? holder : insuredId.trim();
    if (!holder || !insured) return setError({ message: '계약자와 피보험자 ID 를 입력해 주세요.' });
    setBusy(true);
    setError(null);
    try {
      const res = await contractApi.apply({
        ...quotedRequest,
        policyholderId: holder,
        insuredId: insured,
        totalPremium: quote.totalPremium,
        coverages: quote.coverages.map((c) => ({ cvrgCd: c.cvrgCd, isrdAmt: c.isrdAmt, prem: c.prem })),
      }, txId);
      setResult(res);
      notify(`청약을 접수했습니다. (${res.contractNo})`);
    } catch (err) {
      setError(err);
    } finally {
      setBusy(false);
    }
  };

  const reset = () => {
    setInput(initialPremiumInput(input.insrClsCd));
    setPolicyholderId('');
    setInsuredId('');
    setQuote(null);
    setResult(null);
    setError(null);
    setTxId(newTxId());
  };

  if (result) {
    return (
      <div className="page">
        <PageHeader title="청약 입력" />
        <Panel title="청약 접수 완료">
          <div className="result-box">
            <p>청약이 접수되었습니다. 보험료를 완납하면 심사 담당자가 승인할 수 있습니다.</p>
            <dl className="info-grid">
              <div className="info-item"><dt>증권번호</dt><dd className="mono" id="result-contract-no">{result.contractNo}</dd></div>
              <div className="info-item"><dt>청약번호</dt><dd className="mono">{result.applNo}</dd></div>
              <div className="info-item"><dt>총보험료</dt><dd>{money(result.totalPremium)}원</dd></div>
              <div className="info-item"><dt>담보</dt><dd>{result.coverageCount}건</dd></div>
            </dl>
            <div className="form-actions">
              <button type="button" className="btn" onClick={reset}>새 청약 입력</button>
              <Link className="btn btn-primary" to={`/contracts/${encodeURIComponent(result.contractNo)}`}>계약 상세 보기</Link>
            </div>
          </div>
        </Panel>
      </div>
    );
  }

  return (
    <div className="page">
      <PageHeader title="청약 입력" desc="① 가입 조건 입력 → ② 보험료 산출 → ③ 계약자 정보 확인 후 청약 접수 순서로 진행합니다." />
      <div className="two-col wide-left">
        <Panel title="① 가입 조건">
          <ErrorBox error={products.error} />
          <PremiumInputs value={input} onChange={changeInput} products={products.data?.items} disabled={busy} />
          <div className="form-actions">
            <button type="button" className="btn btn-primary" onClick={runQuote} disabled={busy}>② 보험료 산출</button>
          </div>
        </Panel>
        <Panel title="③ 계약자 정보와 접수">
          <div className="form-grid single">
            <Field label="계약자 ID" required hint="고객 시스템의 고객 ID (20자 이하)">
              <input id="policyholder-id" value={policyholderId} maxLength={20} onChange={(e) => setPolicyholderId(e.target.value)} />
            </Field>
            <label className="check">
              <input type="checkbox" checked={sameAsHolder} onChange={(e) => setSameAsHolder(e.target.checked)} />
              피보험자가 계약자와 같음
            </label>
            {!sameAsHolder && (
              <Field label="피보험자 ID" required>
                <input id="insured-id" value={insuredId} maxLength={20} onChange={(e) => setInsuredId(e.target.value)} />
              </Field>
            )}
          </div>
          {quote ? <QuoteResult quote={quote} /> : <p className="muted">먼저 보험료를 산출해 주세요. 조건을 바꾸면 다시 산출해야 합니다.</p>}
          <ErrorBox error={error} onClose={() => setError(null)} />
          <div className="form-actions">
            <span className="muted small mono" title="같은 거래 ID 로 다시 보내면 한 번만 접수됩니다">거래 ID {txId.slice(0, 8)}</span>
            <button type="button" className="btn btn-primary" id="apply-submit" onClick={submit} disabled={!quote || busy}>
              {busy ? '처리 중…' : '청약 접수'}
            </button>
          </div>
        </Panel>
      </div>
    </div>
  );
}

import { useState } from 'react';
import { contractApi } from '../../api/endpoints';
import { newTxId } from '../../api/http';
import { PAY_METHODS, RATING_FIELDS, coverageName } from '../../common/codes';
import { date, money, parseAmount } from '../../common/format';
import { ErrorBox, Field, InfoGrid, Modal, Select } from '../../components/ui';

// 계약 상세의 처리 대화상자. 각 대화상자는 열릴 때 거래 UUID 를 한 번 만들고, 다시 눌러도 같은 값으로 보내 중복 처리를 막는다.

function useSubmit(action, onDone) {
  const [txId] = useState(newTxId);
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);
  const submit = async (...args) => {
    setBusy(true);
    setError(null);
    try {
      const res = await action(txId, ...args);
      onDone(res);
    } catch (err) {
      setError(err);
    } finally {
      setBusy(false);
    }
  };
  return { submit, error, busy };
}

const STATUS_ACTIONS = {
  '20': { title: '청약 승인', verb: '승인', tone: 'btn-primary', desc: '보험료가 완납된 청약을 승인해 유효 계약으로 만듭니다.' },
  '90': { title: '청약 취소', verb: '청약 취소', tone: 'btn-danger', desc: '성립 전 청약을 취소(거절)합니다. 되돌릴 수 없습니다.' },
  '30': { title: '계약 해지', verb: '해지', tone: 'btn-danger', desc: '유효 계약을 오늘 날짜로 해지합니다. 남은 기간에 해당하는 보험료를 환급합니다. 되돌릴 수 없습니다.' },
};

/** 상태 변경 (승인 10→20, 청약취소 10→90, 해지 20→30) */
export function StatusChangeModal({ contract, target, onClose, onDone }) {
  const meta = STATUS_ACTIONS[target];
  const [result, setResult] = useState(null);
  const { submit, error, busy } = useSubmit(
    (txId) => contractApi.statusChange(contract.contractNo, target, txId),
    (res) => (target === '30' ? setResult(res) : onDone(`${meta.verb} 처리했습니다.`)),
  );

  if (result) {
    return (
      <Modal title="해지 완료" onClose={() => onDone('해지 처리했습니다.')}
             footer={<button type="button" className="btn btn-primary" onClick={() => onDone('해지 처리했습니다.')}>확인</button>}>
        <InfoGrid items={[
          ['해지일', date(result.termDate)],
          ['환급보험료', <strong key="r">{money(result.refundPremium)}원</strong>],
        ]} />
      </Modal>
    );
  }

  return (
    <Modal title={meta.title} onClose={onClose}
           footer={(
             <>
               <button type="button" className="btn" onClick={onClose}>닫기</button>
               <button type="button" className={`btn ${meta.tone}`} id="confirm-status" disabled={busy} onClick={() => submit()}>
                 {busy ? '처리 중…' : meta.verb}
               </button>
             </>
           )}>
      <p>{meta.desc}</p>
      <InfoGrid items={[
        ['증권번호', <span key="c" className="mono">{contract.contractNo}</span>],
        ['계약자', contract.policyholderId],
        ['총보험료', `${money(contract.totalPremium)}원`],
        ['보험기간', `${date(contract.startDate)} ~ ${date(contract.endDate)}`],
      ]} />
      <ErrorBox error={error} />
    </Modal>
  );
}

/** 수납 (청약 상태, 분납 가능) */
export function PaymentModal({ contract, remaining, onClose, onDone }) {
  const [amount, setAmount] = useState(money(remaining));
  const [method, setMethod] = useState('TRANSFER');
  const { submit, error, busy } = useSubmit(
    (txId) => contractApi.payment(contract.contractNo, parseAmount(amount), method, txId),
    (res) => onDone(res.fullyPaid ? '수납했습니다. 보험료가 완납되어 승인할 수 있습니다.' : `수납했습니다. 남은 보험료 ${money(res.remainingPremium)}원`),
  );
  const value = parseAmount(amount);
  const invalid = !value || Number.isNaN(value) || value <= 0 || value > remaining;

  return (
    <Modal title="보험료 수납" onClose={onClose}
           footer={(
             <>
               <button type="button" className="btn" onClick={onClose}>닫기</button>
               <button type="button" className="btn btn-primary" id="confirm-payment" disabled={busy || invalid} onClick={() => submit()}>
                 {busy ? '처리 중…' : '수납'}
               </button>
             </>
           )}>
      <InfoGrid items={[
        ['총보험료', `${money(contract.totalPremium)}원`],
        ['남은 보험료', <strong key="r" className="text-danger">{money(remaining)}원</strong>],
      ]} />
      <div className="form-grid">
        <Field label="수납 금액" required hint="나누어 낼 수 있습니다. 남은 보험료를 넘을 수 없습니다.">
          <input id="pay-amount" className="num-input" inputMode="numeric" value={amount} onChange={(e) => setAmount(e.target.value)}
                 onBlur={() => value && !Number.isNaN(value) && setAmount(money(value))} />
        </Field>
        <Field label="수납 방법" required>
          <Select value={method} onChange={setMethod} options={PAY_METHODS} />
        </Field>
      </div>
      <ErrorBox error={error} />
    </Modal>
  );
}

/** 배서: 계약자/피보험자 변경 */
export function EndorseModal({ contract, onClose, onDone }) {
  const [holder, setHolder] = useState(contract.policyholderId);
  const [insured, setInsured] = useState(contract.insuredId);
  const { submit, error, busy } = useSubmit(
    (txId) => contractApi.endorse(contract.contractNo, holder.trim(), insured.trim(), txId),
    (res) => onDone(`배서 처리했습니다. (${res.changes.map((c) => c.fieldNm).join(', ')})`),
  );
  const unchanged = holder.trim() === contract.policyholderId && insured.trim() === contract.insuredId;

  return (
    <Modal title="계약 변경 (배서)" onClose={onClose}
           footer={(
             <>
               <button type="button" className="btn" onClick={onClose}>닫기</button>
               <button type="button" className="btn btn-primary" id="confirm-endorse" disabled={busy || unchanged || !holder.trim() || !insured.trim()}
                       onClick={() => submit()}>
                 {busy ? '처리 중…' : '변경'}
               </button>
             </>
           )}>
      <p className="muted">바뀐 항목마다 변경 전·후 값이 배서 이력으로 남습니다.</p>
      <div className="form-grid">
        <Field label="계약자 ID" required hint={`현재: ${contract.policyholderId}`}>
          <input id="endorse-holder" value={holder} maxLength={20} onChange={(e) => setHolder(e.target.value)} />
        </Field>
        <Field label="피보험자 ID" required hint={`현재: ${contract.insuredId}`}>
          <input id="endorse-insured" value={insured} maxLength={20} onChange={(e) => setInsured(e.target.value)} />
        </Field>
      </div>
      <ErrorBox error={error} />
    </Modal>
  );
}

/** 갱신: 요율 계수를 확인해 갱신 보험료를 미리 보고, 새 청약으로 접수한다 */
export function RenewModal({ contract, onClose, onRenewed }) {
  const fields = RATING_FIELDS[contract.insrClsCd] ?? [];
  const [rating, setRating] = useState(() => Object.fromEntries(fields.map((f) => [f.key, contract[f.key] ?? ''])));
  const [preview, setPreview] = useState(null);
  const [previewError, setPreviewError] = useState(null);
  const [loading, setLoading] = useState(false);

  const ratingBody = () => {
    const body = {};
    for (const f of fields) {
      if (rating[f.key] !== '' && rating[f.key] !== null) body[f.key] = f.type === 'number' ? Number(rating[f.key]) : rating[f.key];
    }
    return Object.keys(body).length ? body : null;
  };

  const runPreview = async () => {
    setLoading(true);
    setPreviewError(null);
    try {
      setPreview(await contractApi.renewQuote(contract.contractNo, ratingBody()));
    } catch (err) {
      setPreview(null);
      setPreviewError(err);
    } finally {
      setLoading(false);
    }
  };

  const { submit, error, busy } = useSubmit(
    (txId) => contractApi.renew(contract.contractNo, ratingBody(), txId),
    (res) => onRenewed(res),
  );

  return (
    <Modal title="계약 갱신" width={680} onClose={onClose}
           footer={(
             <>
               <button type="button" className="btn" onClick={onClose}>닫기</button>
               <button type="button" className="btn" onClick={runPreview} disabled={loading}>{loading ? '계산 중…' : '갱신 보험료 미리보기'}</button>
               <button type="button" className="btn btn-primary" id="confirm-renew" disabled={!preview || busy} onClick={() => submit()}>
                 {busy ? '처리 중…' : '갱신 청약 접수'}
               </button>
             </>
           )}>
      <p className="muted">
        보험종기({date(contract.endDate)})부터 같은 기간, 같은 계약자·담보로 새 청약을 만듭니다. 보험료는 갱신 시점 요율로 다시 산출합니다.
      </p>
      <div className="form-grid">
        {fields.map((f) => (
          <Field key={f.key} label={f.label} hint="비우면 원 계약 값을 씁니다">
            {f.type === 'select' ? (
              <Select value={rating[f.key]} onChange={(v) => { setRating({ ...rating, [f.key]: v }); setPreview(null); }} options={f.options} placeholder="원 계약 값" />
            ) : (
              <input type="number" value={rating[f.key]} onChange={(e) => { setRating({ ...rating, [f.key]: e.target.value }); setPreview(null); }} />
            )}
          </Field>
        ))}
      </div>
      <ErrorBox error={previewError} />
      {preview && (
        <>
          <InfoGrid items={[
            ['갱신 보험기간', `${date(preview.startDate)} ~ ${date(preview.endDate)}`],
            ['현재 보험료', `${money(preview.previousTotalPremium)}원`],
            ['갱신 보험료', <strong key="t">{money(preview.totalPremium)}원</strong>],
            ['차이', <span key="d" className={Number(preview.premiumDifference) > 0 ? 'text-danger' : 'text-ok'}>
              {Number(preview.premiumDifference) > 0 ? '+' : ''}{money(preview.premiumDifference)}원
            </span>],
          ]} />
          <table className="grid compact">
            <thead><tr><th>담보</th><th style={{ textAlign: 'right' }}>가입금액</th><th style={{ textAlign: 'right' }}>현재</th><th style={{ textAlign: 'right' }}>갱신</th></tr></thead>
            <tbody>
              {preview.coverages.map((c) => (
                <tr key={c.cvrgCd}>
                  <td>{coverageName(c.cvrgCd)}</td>
                  <td className="num">{money(c.isrdAmt)}</td>
                  <td className="num">{money(c.previousPrem)}</td>
                  <td className="num">{money(c.prem)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </>
      )}
      <ErrorBox error={error} />
    </Modal>
  );
}

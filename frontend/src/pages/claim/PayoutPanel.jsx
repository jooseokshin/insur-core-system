import { useState } from 'react';
import { payoutApi } from '../../api/endpoints';
import { newTxId } from '../../api/http';
import { useAuth } from '../../auth/AuthProvider';
import { BANKS, PAYOUT_STAT, nameOf } from '../../common/codes';
import { dateTime, money } from '../../common/format';
import { Badge, DataTable, ErrorBox, Field, Modal, Panel, useLoad } from '../../components/ui';

// 보험금(사고)·마일리지 환급금의 펌뱅킹 지급. 금액은 서버가 원천에서 가져오고, 예금주 조회가 맞아야 요청된다.
// 이체는 서버 예약 작업이 보내며, 응답이 없으면 다시 보내지 않고 결과를 조회한다(결과 불명).

export function PayoutStatusBadge({ code }) {
  return <Badge tone={PAYOUT_STAT.find((s) => s.code === code)?.tone}>{nameOf(PAYOUT_STAT, code)}</Badge>;
}

export function PayoutPanel({ srcType, srcNo, payable, amount }) {
  const { hasAny } = useAuth();
  const [version, setVersion] = useState(0);
  const [open, setOpen] = useState(false);
  const list = useLoad(() => payoutApi.list({ srcType, srcNo, limit: 20 }), [srcType, srcNo, version]);
  const active = list.data?.some((p) => p.statCd !== '80');
  const canRequest = payable && !active && hasAny('CLAIM', 'OPERATOR', 'ADMIN');

  return (
    <Panel title={srcType === 'CLAIM' ? '보험금 지급 (계좌 이체)' : '환급금 지급 (계좌 이체)'} actions={(
      <>
        <button type="button" className="btn" onClick={() => setVersion((v) => v + 1)}>새로 고침</button>
        {canRequest && <button type="button" id="payout-request" className="btn btn-primary" onClick={() => setOpen(true)}>지급 요청</button>}
      </>
    )}>
      <ErrorBox error={list.error} />
      {list.data?.some((p) => p.statCd === '70') && (
        <div className="alert alert-warn small">응답을 받지 못한 이체가 있습니다. 같은 이체를 다시 보내면 이중 지급이 될 수 있어 은행에 결과를 조회하는 중입니다.</div>
      )}
      <DataTable rowKey={(r) => r.payoutId} rows={list.data} loading={list.loading}
                 empty={payable ? '아직 지급 요청이 없습니다.' : '지급 결정 후 요청할 수 있습니다.'} columns={[
        { key: 'statCd', title: '상태', render: (r) => <PayoutStatusBadge code={r.statCd} /> },
        { key: 'payAmt', title: '금액', align: 'right', render: (r) => money(r.payAmt) },
        { key: 'account', title: '계좌', render: (r) => <>{nameOf(BANKS, r.bankCd)} <span className="mono">{r.accountMask}</span> {r.holderNm}</> },
        { key: 'traceNo', title: '거래고유번호', render: (r) => <span className="mono small">{r.traceNo}</span> },
        { key: 'counts', title: '전송/조회', align: 'right', render: (r) => `${r.sendCount}/${r.inquiryCount}` },
        { key: 'resultMsg', title: '결과', render: (r) => <span className="wrap small">{r.resultMsg ?? '-'}</span> },
        { key: 'reqDt', title: '요청', render: (r) => `${dateTime(r.reqDt)} (${r.reqEmpId ?? '-'})` },
        { key: 'doneDt', title: '완료', render: (r) => dateTime(r.doneDt) },
      ]} />
      {open && <PayoutRequestModal srcType={srcType} srcNo={srcNo} amount={amount} onClose={() => setOpen(false)}
                                   onDone={() => { setOpen(false); setVersion((v) => v + 1); }} />}
    </Panel>
  );
}

function PayoutRequestModal({ srcType, srcNo, amount, onClose, onDone }) {
  const [txId] = useState(newTxId);
  const [form, setForm] = useState({ bankCd: '004', accountNo: '', holderNm: '' });
  const [verified, setVerified] = useState(null);
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);
  const set = (k, v) => { setForm({ ...form, [k]: v }); setVerified(null); };
  const body = { ...form, accountNo: form.accountNo.replace(/\D/g, ''), holderNm: form.holderNm.trim() };

  const run = async (fn) => {
    setBusy(true);
    setError(null);
    try {
      await fn();
    } catch (err) {
      setError(err);
    } finally {
      setBusy(false);
    }
  };

  return (
    <Modal title="지급 요청" width={560} onClose={onClose}
           footer={<><button type="button" className="btn" onClick={onClose}>닫기</button>
             <button type="button" id="confirm-payout" className="btn btn-primary" disabled={busy || !verified?.matched}
                     onClick={() => run(async () => { await payoutApi.request({ srcType, srcNo, ...body }, txId); onDone(); })}>지급 요청</button></>}>
      <p className="muted">지급액 <strong>{money(amount)}원</strong>을 아래 계좌로 보냅니다. 예금주 조회가 맞아야 요청할 수 있습니다(잘못 보낸 돈은 돌려받기 어렵습니다).</p>
      <div className="form-grid">
        <Field label="은행" required>
          <select value={form.bankCd} onChange={(e) => set('bankCd', e.target.value)}>
            {BANKS.map((b) => <option key={b.code} value={b.code}>{b.name} ({b.code})</option>)}
          </select>
        </Field>
        <Field label="계좌번호" required hint="숫자만 10~16자리"><input id="payout-account" value={form.accountNo} maxLength={20} onChange={(e) => set('accountNo', e.target.value)} /></Field>
        <Field label="예금주" required><input id="payout-holder" value={form.holderNm} maxLength={20} onChange={(e) => set('holderNm', e.target.value)} /></Field>
        <div className="search-actions">
          <button type="button" id="payout-verify" className="btn" disabled={busy || !body.accountNo || !body.holderNm}
                  onClick={() => run(async () => setVerified(await payoutApi.verify(body)))}>예금주 조회</button>
        </div>
      </div>
      {verified && (
        <div className={`alert ${verified.matched ? 'alert-info' : 'alert-warn'}`}>
          {verified.resultMsg}{verified.bankHolderNm && <> (은행 예금주 {verified.bankHolderNm})</>}
        </div>
      )}
      <p className="muted small">개발용 모의 은행 계좌: 004-11122233344 홍길동(정상), 004-55566677777 김지급(이체 후 응답 유실), 020-99900011122 박해지(해지)</p>
      <ErrorBox error={error} />
    </Modal>
  );
}

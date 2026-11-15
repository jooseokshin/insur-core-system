import { useState } from 'react';
import { contractApi } from '../../api/endpoints';
import { newTxId } from '../../api/http';
import { useAuth } from '../../auth/AuthProvider';
import { BANKS, CMS_DEBIT_STAT, CONSENT_METHODS, MANDATE_STAT, nameOf } from '../../common/codes';
import { dateTime, money } from '../../common/format';
import { Badge, DataTable, ErrorBox, Field, InfoGrid, Modal, Panel, useLoad } from '../../components/ui';

// 보험료 자동이체(CMS): 예금주의 출금 동의를 등록하면 매일 아침 배치가 결제원에 등록하고 남은 보험료를 출금해 수납으로 남긴다.

function tone(list, code) {
  return list.find((x) => x.code === code)?.tone;
}

export function AutoDebitPanel({ contract, version, onDone }) {
  const { hasAny } = useAuth();
  const [open, setOpen] = useState(false);
  const [error, setError] = useState(null);
  const data = useLoad(() => contractApi.autoDebitInquiry(contract.contractNo), [contract.contractNo, version]);
  const m = data.data?.mandate;
  const active = m && ['10', '20', '30'].includes(m.statCd);
  const canEdit = hasAny('AGENT', 'OPERATOR', 'ADMIN');

  const cancel = async () => {
    setError(null);
    try {
      await contractApi.autoDebitCancel(contract.contractNo);
      onDone('자동이체를 해지했습니다.');
    } catch (err) {
      setError(err);
    }
  };

  return (
    <Panel title="보험료 자동이체 (CMS)" actions={canEdit && (
      active
        ? <button type="button" className="btn btn-danger-ghost" onClick={cancel}>해지</button>
        : <button type="button" id="autodebit-register" className="btn" onClick={() => setOpen(true)}>자동이체 등록</button>
    )}>
      <ErrorBox error={error || data.error} onClose={() => setError(null)} />
      {!m && <p className="muted">등록된 자동이체가 없습니다. 등록하면 매일 07:00 배치가 결제원 등록 후 남은 보험료를 출금합니다.</p>}
      {m && (
        <InfoGrid items={[
          ['상태', <Badge key="s" tone={tone(MANDATE_STAT, m.statCd)}>{nameOf(MANDATE_STAT, m.statCd)}</Badge>],
          ['출금 계좌', <span key="a">{nameOf(BANKS, m.bankCd)} <span className="mono">{m.accountMask}</span> {m.payerNm}</span>],
          ['출금 동의', `${nameOf(CONSENT_METHODS, m.consentMethod)} (${m.consentRef}) ${dateTime(m.consentDt)}`],
          ['결제원 결과', m.resultMsg ?? '-'],
          ...(m.inheritedFrom ? [['승계', `갱신 원 계약 ${m.inheritedFrom}`]] : []),
        ]} />
      )}
      {data.data?.debits?.length > 0 && (
        <DataTable rowKey={(r) => r.debitId} rows={data.data.debits} columns={[
          { key: 'debitDt', title: '출금일' },
          { key: 'attemptNo', title: '차수', align: 'right', render: (r) => `${r.attemptNo}차` },
          { key: 'amount', title: '금액', align: 'right', render: (r) => money(r.amount) },
          { key: 'statCd', title: '상태', render: (r) => <Badge tone={tone(CMS_DEBIT_STAT, r.statCd)}>{nameOf(CMS_DEBIT_STAT, r.statCd)}</Badge> },
          { key: 'resultMsg', title: '결과', render: (r) => r.resultMsg ?? '-' },
        ]} />
      )}
      {open && <RegisterModal contract={contract} onClose={() => setOpen(false)} onDone={() => { setOpen(false); onDone('자동이체를 등록했습니다.'); }} />}
    </Panel>
  );
}

function RegisterModal({ contract, onClose, onDone }) {
  const [txId] = useState(newTxId);
  const [form, setForm] = useState({ bankCd: '004', accountNo: '', payerNm: '', consentMethod: 'PAPER', consentRef: '' });
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);
  const set = (k, v) => setForm({ ...form, [k]: v });
  const submit = async () => {
    setBusy(true);
    setError(null);
    try {
      await contractApi.autoDebitRegister({ contractNo: contract.contractNo, ...form, accountNo: form.accountNo.replace(/\D/g, ''),
        payerNm: form.payerNm.trim(), consentRef: form.consentRef.trim() }, txId);
      onDone();
    } catch (err) {
      setError(err);
    } finally {
      setBusy(false);
    }
  };
  const invalid = !form.accountNo || !form.payerNm.trim() || !form.consentRef.trim();
  return (
    <Modal title="보험료 자동이체 등록" width={560} onClose={onClose}
           footer={<><button type="button" className="btn" onClick={onClose}>닫기</button>
             <button type="button" id="confirm-autodebit" className="btn btn-primary" disabled={busy || invalid} onClick={submit}>{busy ? '확인 중…' : '등록'}</button></>}>
      <p className="muted">출금 동의는 예금주에게서 받아야 합니다(계약자와 달라도 됩니다). 동의 증빙(신청서·녹취 등)의 관리번호를 남기고 원본은 따로 보관합니다.
        등록할 때 은행 예금주 조회로 계좌를 확인합니다.</p>
      <div className="form-grid">
        <Field label="은행" required>
          <select value={form.bankCd} onChange={(e) => set('bankCd', e.target.value)}>
            {BANKS.map((b) => <option key={b.code} value={b.code}>{b.name} ({b.code})</option>)}
          </select>
        </Field>
        <Field label="계좌번호" required hint="숫자 10~16자리"><input id="autodebit-account" value={form.accountNo} maxLength={20} onChange={(e) => set('accountNo', e.target.value)} /></Field>
        <Field label="예금주" required><input id="autodebit-payer" value={form.payerNm} maxLength={20} onChange={(e) => set('payerNm', e.target.value)} /></Field>
        <Field label="동의 방식" required>
          <select value={form.consentMethod} onChange={(e) => set('consentMethod', e.target.value)}>
            {CONSENT_METHODS.map((c) => <option key={c.code} value={c.code}>{c.name}</option>)}
          </select>
        </Field>
        <Field label="동의 증빙 관리번호" required hint="신청서 번호, 녹취 파일 키 등"><input id="autodebit-ref" value={form.consentRef} maxLength={100} onChange={(e) => set('consentRef', e.target.value)} /></Field>
      </div>
      <p className="muted small">개발용 모의 은행: 004-11122233344 홍길동(잔액 충분), 004-22233344455 박잔고(잔액 1,000원 → 출금 실패)</p>
      <ErrorBox error={error} />
    </Modal>
  );
}

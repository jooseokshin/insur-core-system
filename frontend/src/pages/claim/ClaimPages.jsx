import { useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { claimApi } from '../../api/endpoints';
import { newTxId } from '../../api/http';
import { useAuth } from '../../auth/AuthProvider';
import { CARE_TYPES, CLM_STAT, FACILITY_TYPES, INSR_CLS, MEDICAL_CLS, coverageName, nameOf } from '../../common/codes';
import { date, dateTime, money, parseAmount, seoulDate } from '../../common/format';
import {
  Badge, DataTable, ErrorBox, Field, InfoGrid, Modal, PageHeader, Pagination, Panel, useLoad, useToast,
} from '../../components/ui';

// 보상 화면: 사고 목록, 사고 상세(손해사정·지급·면책·접수 취소), 계약 상세에서 여는 사고 접수 대화상자.
// 지급액 계산(자기부담금, 남은 가입금액)과 고액 승인 규칙은 서버가 정한다. 화면은 결과를 보여 주고 버튼을 알맞게 막는다.

export function ClaimStatusBadge({ code }) {
  return <Badge tone={CLM_STAT.find((s) => s.code === code)?.tone}>{nameOf(CLM_STAT, code)}</Badge>;
}

const LIST_TABS = [
  { code: '', name: '전체' },
  { code: '10', name: '접수' },
  { code: '20', name: '지급 대기' },
  { code: 'HIGH', name: '고액 승인 대기' },
  { code: '40', name: '지급 완료' },
  { code: '80', name: '면책' },
];

export function ClaimListPage() {
  const navigate = useNavigate();
  const { ownContractsOnly } = useAuth();
  const [tab, setTab] = useState('');
  const [contractNo, setContractNo] = useState('');
  const [cond, setCond] = useState({ contractNo: '' });
  const [page, setPage] = useState(0);
  const { data, error, loading } = useLoad(() => claimApi.list({
    clmStatCd: tab && tab !== 'HIGH' ? tab : null,
    highValueOnly: tab === 'HIGH' ? true : null,
    contractNo: cond.contractNo.trim() || null,
    page, size: 20,
  }), [tab, cond, page]);

  return (
    <div className="page">
      <PageHeader title="사고·보상 목록" desc={ownContractsOnly ? '본인이 접수한 계약의 사고만 보입니다.' : '최근 접수순입니다. 행을 누르면 손해사정과 지급을 처리할 수 있습니다.'} />
      <Panel>
        <form className="search-bar" onSubmit={(e) => { e.preventDefault(); setPage(0); setCond({ contractNo }); }}>
          <Field label="증권번호"><input id="claim-contract-no" value={contractNo} onChange={(e) => setContractNo(e.target.value)} /></Field>
          <div className="search-actions"><button type="submit" className="btn btn-primary">조회</button></div>
        </form>
        <div className="tabs" role="tablist">
          {LIST_TABS.map((t) => (
            <button key={t.code} type="button" role="tab" aria-selected={tab === t.code}
                    className={`tab ${tab === t.code ? 'active' : ''}`} onClick={() => { setPage(0); setTab(t.code); }}>
              {t.name}
            </button>
          ))}
        </div>
        <ErrorBox error={error} />
        <DataTable loading={loading} rows={data?.items} rowKey={(r) => r.clmNo} empty="사고가 없습니다."
                   onRowClick={(r) => navigate(`/claims/${encodeURIComponent(r.clmNo)}`)} columns={[
          { key: 'clmNo', title: '사고번호', render: (r) => <span className="mono">{r.clmNo}</span> },
          { key: 'clmStatCd', title: '상태', render: (r) => <><ClaimStatusBadge code={r.clmStatCd} />{r.highValue && r.clmStatCd === '20' && <> <Badge tone="danger">고액</Badge></>}</> },
          { key: 'contractNo', title: '증권번호', render: (r) => <span className="mono">{r.contractNo}</span> },
          { key: 'insrClsCd', title: '종목', render: (r) => nameOf(INSR_CLS, r.insrClsCd) },
          { key: 'acdtDt', title: '사고일', render: (r) => date(r.acdtDt) },
          { key: 'claimantId', title: '청구인' },
          { key: 'totalClaimAmt', title: '청구액', align: 'right', render: (r) => money(r.totalClaimAmt) },
          { key: 'totalPayAmt', title: '지급액', align: 'right', render: (r) => (r.totalPayAmt === null ? '-' : money(r.totalPayAmt)) },
          { key: 'rptDt', title: '접수일시', render: (r) => dateTime(r.rptDt) },
        ]} />
        {data && <Pagination page={data.page} totalPages={data.totalPages} totalElements={data.totalElements} onChange={setPage} />}
      </Panel>
    </div>
  );
}

/** 처리 대화상자 공통: 열릴 때 거래 UUID 를 한 번 만들고 재시도에도 그대로 쓴다 */
function useAction(run, onDone) {
  const [txId] = useState(newTxId);
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);
  const submit = async () => {
    setBusy(true);
    setError(null);
    try {
      onDone(await run(txId));
    } catch (err) {
      setError(err);
    } finally {
      setBusy(false);
    }
  };
  return { submit, error, busy };
}

function AssessModal({ claim, onClose, onDone }) {
  const [amounts, setAmounts] = useState(() => Object.fromEntries(claim.items.map((i) => [i.cvrgCd, money(i.assessedAmt ?? i.claimAmt)])));
  const [memo, setMemo] = useState('');
  const parsed = claim.items.map((i) => ({ cvrgCd: i.cvrgCd, amount: parseAmount(amounts[i.cvrgCd]) }));
  const invalid = parsed.some((p) => p.amount === null || Number.isNaN(p.amount));
  const { submit, error, busy } = useAction(
    (txId) => claimApi.assess(claim.clmNo, parsed, memo.trim() || null, txId),
    (res) => onDone(`손해사정을 기록했습니다. 지급액 ${money(res.totalPayAmt)}원`),
  );
  return (
    <Modal title={`손해사정 - ${claim.clmNo}`} width={640} onClose={onClose}
           footer={<><button type="button" className="btn" onClick={onClose}>닫기</button>
             <button type="button" id="confirm-assess" className="btn btn-primary" disabled={busy || invalid} onClick={submit}>{busy ? '처리 중…' : '사정 결과 저장'}</button></>}>
      <p className="muted">담보별 사정 손해액을 입력하면 서버가 자기부담금과 남은 가입금액을 반영해 지급액을 계산합니다.</p>
      <table className="grid compact">
        <thead><tr><th>담보</th><th style={{ textAlign: 'right' }}>가입금액</th><th style={{ textAlign: 'right' }}>기지급</th><th style={{ textAlign: 'right' }}>청구액</th><th style={{ textAlign: 'right' }}>사정 손해액</th></tr></thead>
        <tbody>
          {claim.items.map((i) => (
            <tr key={i.cvrgCd}>
              <td>{coverageName(i.cvrgCd)}</td>
              <td className="num">{money(i.isrdAmt)}</td>
              <td className="num">{money(i.paidByOthers)}</td>
              <td className="num">{money(i.claimAmt)}</td>
              <td style={{ textAlign: 'right' }}>
                <input id={`assess-${i.cvrgCd}`} className="num-input" inputMode="numeric" value={amounts[i.cvrgCd]}
                       onChange={(e) => setAmounts({ ...amounts, [i.cvrgCd]: e.target.value })}
                       onBlur={(e) => { const n = parseAmount(e.target.value); if (n !== null && !Number.isNaN(n)) setAmounts({ ...amounts, [i.cvrgCd]: money(n) }); }} />
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      <Field label="사정 의견"><input value={memo} maxLength={500} onChange={(e) => setMemo(e.target.value)} /></Field>
      <ErrorBox error={error} />
    </Modal>
  );
}

/** 실손 손해사정: 금액을 직접 넣지 않고 진료별 인정액만 조정하면 서버가 공제·한도·비례보상을 계산한다 */
function MedicalAssessModal({ claim, onClose, onDone }) {
  const [rows, setRows] = useState(() => Object.fromEntries(claim.medicalItems.map((m) => [m.seq, {
    covered: money(m.coveredAssessed ?? m.coveredAmt), nonCovered: money(m.nonCoveredAssessed ?? m.nonCoveredAmt),
  }])));
  const [memo, setMemo] = useState('');
  const adjustments = claim.medicalItems.map((m) => ({
    seq: m.seq, coveredAmt: parseAmount(rows[m.seq].covered), nonCoveredAmt: parseAmount(rows[m.seq].nonCovered),
  }));
  const invalid = adjustments.some((a, i) => [a.coveredAmt, a.nonCoveredAmt].some((v) => v === null || Number.isNaN(v))
    || a.coveredAmt > Number(claim.medicalItems[i].coveredAmt) || a.nonCoveredAmt > Number(claim.medicalItems[i].nonCoveredAmt));
  const { submit, error, busy } = useAction(
    (txId) => claimApi.assess(claim.clmNo, null, memo.trim() || null, txId, adjustments),
    (res) => onDone(`실손 손해사정을 계산했습니다. 지급액 ${money(res.totalPayAmt)}원`),
  );
  const set = (seq, key, value) => setRows({ ...rows, [seq]: { ...rows[seq], [key]: value } });
  return (
    <Modal title={`실손 손해사정 - ${claim.clmNo}`} width={760} onClose={onClose}
           footer={<><button type="button" className="btn" onClick={onClose}>닫기</button>
             <button type="button" id="confirm-assess" className="btn btn-primary" disabled={busy || invalid} onClick={submit}>{busy ? '계산 중…' : '계산하고 저장'}</button></>}>
      <p className="muted">
        인정액은 청구액 이하로만 줄일 수 있습니다(보장하지 않는 비급여 등). 저장하면 서버가 4세대 공제(급여 20%·비급여 30%, 통원 최소 공제),
        통원 1회 한도, 연간 한도를 적용하고 신용정보원에서 다른 보험사 실손을 조회해 비례보상합니다.
      </p>
      <table className="grid compact">
        <thead><tr><th>#</th><th>진료일</th><th>구분</th><th>의료기관</th><th style={{ textAlign: 'right' }}>급여 인정</th><th style={{ textAlign: 'right' }}>비급여 인정</th></tr></thead>
        <tbody>
          {claim.medicalItems.map((m) => (
            <tr key={m.seq}>
              <td>{m.seq}</td>
              <td>{date(m.treatDt)}</td>
              <td>{nameOf(CARE_TYPES, m.careType)} · {nameOf(FACILITY_TYPES, m.facilityType)}</td>
              <td>{m.facilityNm}</td>
              {['covered', 'nonCovered'].map((k) => (
                <td key={k} style={{ textAlign: 'right' }}>
                  <input className="num-input" inputMode="numeric" value={rows[m.seq][k]} onChange={(e) => set(m.seq, k, e.target.value)}
                         title={`청구 ${money(k === 'covered' ? m.coveredAmt : m.nonCoveredAmt)}원`} />
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
      <Field label="사정 의견"><input value={memo} maxLength={500} onChange={(e) => setMemo(e.target.value)} /></Field>
      <ErrorBox error={error} />
    </Modal>
  );
}

function MedicalPanels({ claim }) {
  return (
    <>
      <Panel title="실손 진료 내역">
        <DataTable rows={claim.medicalItems} rowKey={(r) => r.seq} columns={[
          { key: 'seq', title: '#' },
          { key: 'treatDt', title: '진료일', render: (r) => date(r.treatDt) },
          { key: 'care', title: '구분', render: (r) => `${nameOf(CARE_TYPES, r.careType)} · ${nameOf(FACILITY_TYPES, r.facilityType)}` },
          { key: 'facilityNm', title: '의료기관', render: (r) => <>{r.facilityNm} {r.sourceCd === 'KIDI' && <Badge tone="info">실손24</Badge>}</> },
          { key: 'coveredAmt', title: '급여 청구', align: 'right', render: (r) => money(r.coveredAmt) },
          { key: 'coveredDeduct', title: '급여 공제', align: 'right', render: (r) => money(r.coveredDeduct) },
          { key: 'coveredPay', title: '급여 책임액', align: 'right', render: (r) => money(r.coveredPay) },
          { key: 'nonCoveredAmt', title: '비급여 청구', align: 'right', render: (r) => money(r.nonCoveredAmt) },
          { key: 'nonCoveredDeduct', title: '비급여 공제', align: 'right', render: (r) => (r.nonCoveredDeduct == null ? '-' : money(r.nonCoveredDeduct)) },
          { key: 'nonCoveredPay', title: '비급여 책임액', align: 'right', render: (r) => (r.nonCoveredPay == null ? '미가입' : money(r.nonCoveredPay)) },
        ]} />
        <p className="muted small">공제 = max(최소 공제금액, 의료비 × 자기부담률), 책임액 = 의료비 − 공제 (통원은 1회 한도까지). 지급액은 책임액 합계에 비례보상과 연간 한도를 적용한 값입니다.</p>
      </Panel>
      {claim.medicalShares?.length > 0 && (
        <Panel title="비례보상 (다른 보험사 실손)">
          <DataTable rows={claim.medicalShares} rowKey={(r) => `${r.cvrgCd}-${r.insurerCd}-${r.policyNo}`} columns={[
            { key: 'cvrgCd', title: '담보', render: (r) => coverageName(r.cvrgCd) },
            { key: 'insurerCd', title: '보험회사', render: (r) => <>{r.insurerCd} {r.own && <Badge tone="ok">우리 회사</Badge>}</> },
            { key: 'policyNo', title: '증권번호', render: (r) => <span className="mono">{r.policyNo}</span> },
            { key: 'liabilityAmt', title: '단독 책임액', align: 'right', render: (r) => money(r.liabilityAmt) },
            { key: 'shareAmt', title: '분담액', align: 'right', render: (r) => <strong>{money(r.shareAmt)}</strong> },
          ]} />
          <p className="muted small">실손은 실제 의료비보다 많이 보상하지 않으므로, 책임액 합계가 손해액을 넘으면 손해액을 책임액 비율로 나눕니다. 다른 회사 책임액은 같은 4세대 규칙으로 가정한 값입니다.</p>
        </Panel>
      )}
    </>
  );
}

const DECISIONS = {
  PAY: { title: '보험금 지급', verb: '지급', tone: 'btn-primary', desc: '사정한 지급액으로 보험금을 지급하고 사고를 종결합니다.' },
  DENY: { title: '면책 종결', verb: '면책', tone: 'btn-danger', desc: '보험금을 지급하지 않고 종결합니다. 사유가 필요합니다.' },
  WITHDRAW: { title: '접수 취소', verb: '접수 취소', tone: 'btn-danger', desc: '손해사정 전의 사고 접수를 취소합니다.' },
};

function DecideModal({ claim, decision, onClose, onDone }) {
  const meta = DECISIONS[decision];
  const [memo, setMemo] = useState('');
  const { submit, error, busy } = useAction(
    (txId) => claimApi.decide(claim.clmNo, decision, memo.trim() || null, txId),
    () => onDone(`${meta.verb} 처리했습니다.`),
  );
  return (
    <Modal title={meta.title} onClose={onClose}
           footer={<><button type="button" className="btn" onClick={onClose}>닫기</button>
             <button type="button" id="confirm-decide" className={`btn ${meta.tone}`} disabled={busy || (decision === 'DENY' && !memo.trim())}
                     onClick={submit}>{busy ? '처리 중…' : meta.verb}</button></>}>
      <p>{meta.desc}</p>
      {decision === 'PAY' && <InfoGrid items={[['지급액', <strong key="p">{money(claim.totalPayAmt)}원</strong>], ['청구인', claim.claimantId]]} />}
      <Field label={decision === 'DENY' ? '면책 사유' : '메모'} required={decision === 'DENY'}>
        <input id="decide-memo" value={memo} maxLength={500} onChange={(e) => setMemo(e.target.value)} />
      </Field>
      <ErrorBox error={error} />
    </Modal>
  );
}

export function ClaimDetailPage() {
  const { clmNo } = useParams();
  const navigate = useNavigate();
  const notify = useToast();
  const { hasAny } = useAuth();
  const [version, setVersion] = useState(0);
  const [modal, setModal] = useState(null);
  const { data: c, error } = useLoad(() => claimApi.inquiry(clmNo), [clmNo, version]);

  if (error) return <div className="page"><PageHeader title="사고 상세" /><ErrorBox error={error} /><Link to="/claims" className="btn">사고 목록으로</Link></div>;
  if (!c) return <div className="page"><p className="muted">불러오는 중…</p></div>;

  const handler = hasAny('CLAIM', 'ADMIN');
  const open = c.clmStatCd === '10' || c.clmStatCd === '20';
  const needsAdmin = c.highValue && !hasAny('ADMIN');
  const done = (message) => { setModal(null); notify(message); setVersion((v) => v + 1); };
  const medical = c.contract.insrClsCd === MEDICAL_CLS && !!c.medicalItems;

  return (
    <div className="page">
      <PageHeader
        title={<>사고 상세 <span className="mono title-no">{c.clmNo}</span></>}
        desc={<>증권번호 <Link to={`/contracts/${encodeURIComponent(c.contract.contractNo)}`} className="mono">{c.contract.contractNo}</Link></>}
        actions={(
          <>
            <button type="button" className="btn" onClick={() => navigate(-1)}>목록</button>
            {handler && open && <button type="button" id="action-assess" className="btn" onClick={() => setModal('ASSESS')}>손해사정</button>}
            {handler && c.clmStatCd === '20' && (
              <button type="button" id="action-pay" className="btn btn-primary" disabled={needsAdmin || Number(c.totalPayAmt) === 0}
                      title={needsAdmin ? '고액 지급은 관리자 승인이 필요합니다' : undefined} onClick={() => setModal('PAY')}>지급</button>
            )}
            {handler && open && <button type="button" id="action-deny" className="btn btn-danger-ghost" onClick={() => setModal('DENY')}>면책</button>}
            {c.clmStatCd === '10' && <button type="button" id="action-withdraw" className="btn btn-danger-ghost" onClick={() => setModal('WITHDRAW')}>접수 취소</button>}
          </>
        )}
      />
      {c.clmStatCd === '20' && c.highValue && (
        <div className="alert alert-warn">
          지급액이 {money(c.highValueThreshold)}원을 넘는 고액 건입니다. {hasAny('ADMIN') ? '관리자 승인으로 지급할 수 있습니다.' : '관리자가 지급해야 합니다.'}
        </div>
      )}
      <Panel title="사고 정보">
        <InfoGrid items={[
          ['보상 상태', <ClaimStatusBadge key="s" code={c.clmStatCd} />],
          ['사고일', date(c.acdtDt)],
          ['청구인', c.claimantId],
          ['청구액 합계', `${money(c.totalClaimAmt)}원`],
          ['지급액 합계', c.totalPayAmt === null ? '손해사정 전' : <strong key="p">{money(c.totalPayAmt)}원</strong>],
          ['접수', `${dateTime(c.rptDt)} (${c.rptEmpId ?? '-'})`],
          ['손해사정', c.adjDt ? `${dateTime(c.adjDt)} (${c.adjEmpId})` : '-'],
          ['종결', c.decideDt ? `${dateTime(c.decideDt)} (${c.decideEmpId})` : '-'],
          ['보장 기간', `${date(c.contract.startDate)} ~ ${date(c.contract.termDate ?? c.contract.endDate)}`],
          ['피보험자', c.contract.insuredId],
        ]} />
        <p className="claim-desc"><span className="field-label">사고 내용</span> {c.acdtDesc}</p>
        {c.decideMemo && <p className="claim-desc"><span className="field-label">종결 메모</span> {c.decideMemo}</p>}
      </Panel>
      <Panel title="담보별 금액">
        <DataTable rows={c.items} rowKey={(r) => r.cvrgCd} columns={[
          { key: 'cvrgCd', title: '담보', render: (r) => <><span className="mono muted">{r.cvrgCd}</span> {coverageName(r.cvrgCd)}</> },
          { key: 'isrdAmt', title: '가입금액', align: 'right', render: (r) => money(r.isrdAmt) },
          { key: 'paidByOthers', title: '기지급(다른 사고)', align: 'right', render: (r) => money(r.paidByOthers) },
          { key: 'claimAmt', title: '청구액', align: 'right', render: (r) => money(r.claimAmt) },
          { key: 'assessedAmt', title: '사정 손해액', align: 'right', render: (r) => money(r.assessedAmt) },
          { key: 'deductibleAmt', title: '자기부담금', align: 'right', render: (r) => money(r.deductibleAmt) },
          { key: 'payAmt', title: '지급액', align: 'right', render: (r) => <strong>{money(r.payAmt)}</strong> },
        ]} />
        <p className="muted small">{medical
          ? '실손: 사정 손해액 = 인정 의료비 합계, 자기부담금 칸 = 공제·통원 한도로 빠진 금액, 지급액 = 비례보상 분담액과 남은 연간 한도 중 작은 값'
          : '지급액 = min(사정 손해액 − 자기부담금, 남은 가입금액). 남은 가입금액 = 가입금액 − 같은 담보에 다른 사고로 이미 지급한 금액'}</p>
      </Panel>
      {medical && <MedicalPanels claim={c} />}
      <Panel title="처리 이력">
        <DataTable rows={c.histories} columns={[
          { key: 'chgDt', title: '일시', render: (r) => dateTime(r.chgDt) },
          { key: 'before', title: '변경 전', render: (r) => (r.beforeStatCd ? nameOf(CLM_STAT, r.beforeStatCd) : '(접수)') },
          { key: 'after', title: '변경 후', render: (r) => <ClaimStatusBadge code={r.afterStatCd} /> },
          { key: 'empId', title: '처리자' },
          { key: 'memo', title: '메모', render: (r) => <span className="wrap">{r.memo ?? '-'}</span> },
        ]} />
      </Panel>
      {modal === 'ASSESS' && (medical
        ? <MedicalAssessModal claim={c} onClose={() => setModal(null)} onDone={done} />
        : <AssessModal claim={c} onClose={() => setModal(null)} onDone={done} />)}
      {modal && modal !== 'ASSESS' && <DecideModal claim={c} decision={modal} onClose={() => setModal(null)} onDone={done} />}
    </div>
  );
}

/** 계약 상세에서 여는 사고 접수. 보장 기간과 가입 담보는 계약에서 가져온다 */
export function ClaimReportModal({ contract, onClose, onReported }) {
  if (contract.insrClsCd === MEDICAL_CLS) return <MedicalReportModal contract={contract} onClose={onClose} onReported={onReported} />;
  return <GeneralReportModal contract={contract} onClose={onClose} onReported={onReported} />;
}

const emptyTreatment = () => ({ treatDt: seoulDate(), careType: 'OUT', facilityType: 'CLINIC', facilityNm: '', coveredAmt: '', nonCoveredAmt: '' });

/** 실손 사고 접수: 진료비 영수증 한 장씩 입력한다. 사고일은 가장 이른 진료일, 담보 금액은 서버가 합산한다 */
function MedicalReportModal({ contract, onClose, onReported }) {
  const coverEnd = contract.termDate && contract.termDate < contract.endDate ? contract.termDate : contract.endDate;
  const [desc, setDesc] = useState('');
  const [claimant, setClaimant] = useState(contract.insuredId);
  const [items, setItems] = useState([emptyTreatment()]);
  const parsed = items.map((i) => ({ ...i, facilityNm: i.facilityNm.trim(), coveredAmt: parseAmount(i.coveredAmt) ?? 0, nonCoveredAmt: parseAmount(i.nonCoveredAmt) ?? 0 }));
  const invalid = !desc.trim() || parsed.some((i) => !i.treatDt || !i.facilityNm || Number.isNaN(i.coveredAmt) || Number.isNaN(i.nonCoveredAmt)
    || i.coveredAmt + i.nonCoveredAmt <= 0);
  const { submit, error, busy } = useAction(
    (txId) => claimApi.report({ contractNo: contract.contractNo, acdtDesc: desc.trim(), claimantId: claimant.trim() || null, medicalItems: parsed }, txId),
    (res) => onReported(res),
  );
  const set = (idx, key, value) => setItems(items.map((it, i) => (i === idx ? { ...it, [key]: value } : it)));
  const hasNonCovered = contract.coverages.some((cv) => cv.cvrgCd === 'ME002');
  return (
    <Modal title="실손 사고 접수" width={860} onClose={onClose}
           footer={<><button type="button" className="btn" onClick={onClose}>닫기</button>
             <button type="button" id="confirm-report" className="btn btn-primary" disabled={busy || invalid} onClick={submit}>{busy ? '처리 중…' : '사고 접수'}</button></>}>
      <p className="muted">
        진료비 영수증 한 장(진료일·의료기관 1회)을 한 줄로 입력합니다. 진료일은 {date(contract.startDate)} ~ {date(coverEnd)} 전날 사이여야 합니다.
        {!hasNonCovered && ' 비급여 특약에 가입하지 않아 비급여 금액은 기록만 하고 보상하지 않습니다.'}
        {' '}의료기관이 실손24(보험개발원)로 보낸 청구는 자동으로 접수됩니다.
      </p>
      <div className="form-grid">
        <Field label="사고 내용" required><input id="acdt-desc" value={desc} maxLength={500} onChange={(e) => setDesc(e.target.value)} placeholder="예: 허리 통증으로 통원 치료" /></Field>
        <Field label="청구인 ID" hint="비우면 피보험자"><input value={claimant} maxLength={20} onChange={(e) => setClaimant(e.target.value)} /></Field>
      </div>
      <table className="grid compact">
        <thead><tr><th>진료일</th><th>구분</th><th>종별</th><th>의료기관명</th><th style={{ textAlign: 'right' }}>급여 본인부담</th><th style={{ textAlign: 'right' }}>비급여</th><th /></tr></thead>
        <tbody>
          {items.map((it, idx) => (
            <tr key={idx}>
              <td><input type="date" value={it.treatDt} min={contract.startDate} max={seoulDate()} onChange={(e) => set(idx, 'treatDt', e.target.value)} /></td>
              <td><select value={it.careType} onChange={(e) => set(idx, 'careType', e.target.value)}>{CARE_TYPES.map((o) => <option key={o.code} value={o.code}>{o.name}</option>)}</select></td>
              <td><select value={it.facilityType} onChange={(e) => set(idx, 'facilityType', e.target.value)}>{FACILITY_TYPES.map((o) => <option key={o.code} value={o.code}>{o.name}</option>)}</select></td>
              <td><input value={it.facilityNm} maxLength={100} onChange={(e) => set(idx, 'facilityNm', e.target.value)} /></td>
              <td><input className="num-input" inputMode="numeric" value={it.coveredAmt} placeholder="0" onChange={(e) => set(idx, 'coveredAmt', e.target.value)} /></td>
              <td><input className="num-input" inputMode="numeric" value={it.nonCoveredAmt} placeholder="0" onChange={(e) => set(idx, 'nonCoveredAmt', e.target.value)} /></td>
              <td>{items.length > 1 && <button type="button" className="btn btn-danger-ghost" onClick={() => setItems(items.filter((_, i) => i !== idx))}>삭제</button>}</td>
            </tr>
          ))}
        </tbody>
      </table>
      {items.length < 50 && <button type="button" className="btn" onClick={() => setItems([...items, emptyTreatment()])}>영수증 추가</button>}
      <ErrorBox error={error} />
    </Modal>
  );
}

function GeneralReportModal({ contract, onClose, onReported }) {
  const coverEnd = contract.termDate && contract.termDate < contract.endDate ? contract.termDate : contract.endDate;
  const [acdtDt, setAcdtDt] = useState(seoulDate() < coverEnd ? seoulDate() : '');
  const [desc, setDesc] = useState('');
  const [claimant, setClaimant] = useState(contract.insuredId);
  const [amounts, setAmounts] = useState({});
  const chosen = contract.coverages
    .map((cv) => ({ cvrgCd: cv.cvrgCd, amount: parseAmount(amounts[cv.cvrgCd]) }))
    .filter((x) => x.amount !== null);
  const invalid = !acdtDt || !desc.trim() || chosen.length === 0 || chosen.some((x) => Number.isNaN(x.amount) || x.amount <= 0);
  const { submit, error, busy } = useAction(
    (txId) => claimApi.report({ contractNo: contract.contractNo, acdtDt, acdtDesc: desc.trim(), claimantId: claimant.trim() || null, coverages: chosen }, txId),
    (res) => onReported(res),
  );
  return (
    <Modal title="사고 접수" width={640} onClose={onClose}
           footer={<><button type="button" className="btn" onClick={onClose}>닫기</button>
             <button type="button" id="confirm-report" className="btn btn-primary" disabled={busy || invalid} onClick={submit}>{busy ? '처리 중…' : '사고 접수'}</button></>}>
      <p className="muted">보장 기간 {date(contract.startDate)} ~ {date(coverEnd)} 전날까지의 사고만 접수할 수 있습니다. 청구할 담보에만 추정 손해액을 넣어 주세요.</p>
      <div className="form-grid">
        <Field label="사고일" required>
          <input id="acdt-dt" type="date" value={acdtDt} min={contract.startDate} max={seoulDate()} onChange={(e) => setAcdtDt(e.target.value)} />
        </Field>
        <Field label="청구인 ID" hint="비우면 피보험자"><input value={claimant} maxLength={20} onChange={(e) => setClaimant(e.target.value)} /></Field>
      </div>
      <Field label="사고 내용" required wide><input id="acdt-desc" value={desc} maxLength={500} onChange={(e) => setDesc(e.target.value)} placeholder="예: 교차로에서 신호 대기 중 후방 추돌" /></Field>
      <table className="grid compact">
        <thead><tr><th>담보</th><th style={{ textAlign: 'right' }}>가입금액</th><th style={{ textAlign: 'right' }}>추정 손해액</th></tr></thead>
        <tbody>
          {contract.coverages.map((cv) => (
            <tr key={cv.cvrgCd}>
              <td>{coverageName(cv.cvrgCd)}</td>
              <td className="num">{money(cv.isrdAmt)}</td>
              <td style={{ textAlign: 'right' }}>
                <input id={`claim-${cv.cvrgCd}`} className="num-input" inputMode="numeric" placeholder="청구 안 함" value={amounts[cv.cvrgCd] ?? ''}
                       onChange={(e) => setAmounts({ ...amounts, [cv.cvrgCd]: e.target.value })}
                       onBlur={(e) => { const n = parseAmount(e.target.value); if (n && !Number.isNaN(n)) setAmounts({ ...amounts, [cv.cvrgCd]: money(n) }); }} />
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      <ErrorBox error={error} />
    </Modal>
  );
}

/** 계약 상세의 '사고 내역' 탭 */
export function ContractClaims({ contractNo }) {
  const navigate = useNavigate();
  const { data, error, loading } = useLoad(() => claimApi.list({ contractNo, size: 50 }), [contractNo]);
  return (
    <>
      <ErrorBox error={error} />
      <DataTable loading={loading} rows={data?.items} rowKey={(r) => r.clmNo} empty="사고 내역이 없습니다."
                 onRowClick={(r) => navigate(`/claims/${encodeURIComponent(r.clmNo)}`)} columns={[
        { key: 'clmNo', title: '사고번호', render: (r) => <span className="mono">{r.clmNo}</span> },
        { key: 'clmStatCd', title: '상태', render: (r) => <ClaimStatusBadge code={r.clmStatCd} /> },
        { key: 'acdtDt', title: '사고일', render: (r) => date(r.acdtDt) },
        { key: 'totalClaimAmt', title: '청구액', align: 'right', render: (r) => money(r.totalClaimAmt) },
        { key: 'totalPayAmt', title: '지급액', align: 'right', render: (r) => (r.totalPayAmt === null ? '-' : money(r.totalPayAmt)) },
      ]} />
    </>
  );
}

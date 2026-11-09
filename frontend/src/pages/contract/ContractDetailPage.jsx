import { useEffect, useState } from 'react';
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { contractApi } from '../../api/endpoints';
import { useAuth } from '../../auth/AuthProvider';
import { CTR_STAT, INSR_CLS, PAY_METHODS, RATING_FIELDS, SALES_CHNL, coverageName, nameOf } from '../../common/codes';
import { date, dateTime, money } from '../../common/format';
import { DataTable, ErrorBox, InfoGrid, PageHeader, Panel, StatusBadge, useLoad, useToast } from '../../components/ui';
import { EndorseModal, PaymentModal, RenewModal, StatusChangeModal } from './ContractActions';
import { ClaimReportModal, ContractClaims } from '../claim/ClaimPages';
import { MileagePanel } from './MileagePanel';

const TABS = [
  { code: 'coverages', name: '담보' },
  { code: 'payments', name: '납입 내역' },
  { code: 'status', name: '상태 이력' },
  { code: 'endorse', name: '배서 이력' },
  { code: 'claims', name: '사고 내역' },
];

export function ContractDetailPage() {
  const { contractNo } = useParams();
  const [params, setParams] = useSearchParams();
  const navigate = useNavigate();
  const notify = useToast();
  const { hasAny } = useAuth();
  const [tab, setTab] = useState('coverages');
  const [modal, setModal] = useState(null);
  const [version, setVersion] = useState(0);

  const contract = useLoad(() => contractApi.inquiry(contractNo), [contractNo, version]);
  const payments = useLoad(() => contractApi.paymentHistory(contractNo), [contractNo, version]);
  const statusHist = useLoad(() => (tab === 'status' ? contractApi.statusHistory(contractNo) : Promise.resolve(null)), [contractNo, tab, version]);
  const endorseHist = useLoad(() => (tab === 'endorse' ? contractApi.endorseHistory(contractNo) : Promise.resolve(null)), [contractNo, tab, version]);

  // 만기·갱신 대상 화면에서 '갱신'을 누르고 들어오면 갱신 대화상자를 바로 연다
  useEffect(() => {
    if (params.get('action') === 'renew' && contract.data?.ctrStatCd === '20') {
      setModal({ type: 'renew' });
      setParams({}, { replace: true });
    }
  }, [params, contract.data, setParams]);

  const done = (message) => {
    setModal(null);
    notify(message);
    setVersion((v) => v + 1);
  };

  if (contract.error) {
    return (
      <div className="page">
        <PageHeader title="계약 상세" />
        <ErrorBox error={contract.error} />
        <Link to="/contracts" className="btn">계약 조회로</Link>
      </div>
    );
  }
  const c = contract.data;
  if (!c) return <div className="page"><p className="muted">불러오는 중…</p></div>;

  const remaining = Number(payments.data?.remainingPremium ?? c.totalPremium);
  const applied = c.ctrStatCd === '10';
  const inForce = c.ctrStatCd === '20';
  const actions = [];
  if (applied && hasAny('OPERATOR', 'ADMIN') && remaining > 0) {
    actions.push({ key: 'pay', label: '수납', tone: 'btn-primary', onClick: () => setModal({ type: 'pay' }) });
  }
  if (applied && hasAny('UNDERWRITER', 'ADMIN')) {
    actions.push({
      key: 'approve', label: '승인', tone: 'btn-primary', disabled: remaining > 0,
      title: remaining > 0 ? '보험료를 완납해야 승인할 수 있습니다' : undefined,
      onClick: () => setModal({ type: 'status', target: '20' }),
    });
    actions.push({ key: 'cancel', label: '청약 취소', tone: 'btn-danger-ghost', onClick: () => setModal({ type: 'status', target: '90' }) });
  }
  if ((applied || inForce) && hasAny('UNDERWRITER', 'OPERATOR', 'ADMIN')) {
    actions.push({ key: 'endorse', label: '배서', onClick: () => setModal({ type: 'endorse' }) });
  }
  if (inForce && hasAny('AGENT', 'ADMIN')) {
    actions.push({ key: 'renew', label: '갱신', onClick: () => setModal({ type: 'renew' }) });
  }
  if ((inForce || c.ctrStatCd === '30') && hasAny('AGENT', 'CLAIM', 'ADMIN')) {
    actions.push({ key: 'claim', label: '사고 접수', onClick: () => setModal({ type: 'claim' }) });
  }
  if (inForce && hasAny('OPERATOR', 'ADMIN')) {
    actions.push({ key: 'terminate', label: '해지', tone: 'btn-danger-ghost', onClick: () => setModal({ type: 'status', target: '30' }) });
  }

  const ratingItems = (RATING_FIELDS[c.insrClsCd] ?? []).map((f) => [
    f.label,
    f.type === 'select' ? (f.options.find((o) => o.code === c[f.key])?.name ?? c[f.key] ?? '-') : (c[f.key] ? `만 ${c[f.key]}세` : '-'),
  ]);

  return (
    <div className="page">
      <PageHeader
        title={<>계약 상세 <span className="mono title-no">{c.contractNo}</span></>}
        desc={<>청약번호 <span className="mono">{c.applNo}</span></>}
        actions={(
          <>
            <button type="button" className="btn" onClick={() => navigate(-1)}>목록</button>
            {actions.map((a) => (
              <button key={a.key} type="button" id={`action-${a.key}`} className={`btn ${a.tone ?? ''}`} disabled={a.disabled}
                      title={a.title} onClick={a.onClick}>
                {a.label}
              </button>
            ))}
          </>
        )}
      />

      {applied && remaining > 0 && (
        <div className="alert alert-warn">
          미납 보험료 <strong>{money(remaining)}원</strong>이 남아 있습니다. 완납해야 승인할 수 있습니다.
        </div>
      )}
      {c.renewedFrom && (
        <div className="alert alert-info">
          갱신 계약입니다. 원 계약: <Link to={`/contracts/${encodeURIComponent(c.renewedFrom)}`} className="mono">{c.renewedFrom}</Link>
        </div>
      )}

      <Panel title="계약 정보">
        <InfoGrid items={[
          ['계약상태', <StatusBadge key="s" code={c.ctrStatCd} />],
          ['보험종목', nameOf(INSR_CLS, c.insrClsCd)],
          ['상품', c.prdNm ? `${c.prdNm} (${c.prdCd})` : '상품 없음 (상품 도입 전 방식)'],
          ['판매채널', nameOf(SALES_CHNL, c.salesChnlCd)],
          ['보험기간', `${date(c.startDate)} ~ ${date(c.endDate)}`],
          ['총보험료', <strong key="p">{money(c.totalPremium)}원</strong>],
          ['계약자', c.policyholderId],
          ['피보험자', c.insuredId],
          ...ratingItems,
          ['납입 합계', payments.data ? `${money(payments.data.paidPremium)}원` : '-'],
          ['접수일시', dateTime(c.sysRegDt)],
          ['접수자', c.regEmpId],
          ...(c.termDate ? [['해지일', date(c.termDate)], ['환급보험료', `${money(c.refundPremium)}원`]] : []),
        ]} />
      </Panel>

      {c.insrClsCd === '01' && <MileagePanel contract={c} version={version} onDone={done} />}

      <Panel>
        <div className="tabs" role="tablist">
          {TABS.map((t) => (
            <button key={t.code} type="button" role="tab" aria-selected={tab === t.code}
                    className={`tab ${tab === t.code ? 'active' : ''}`} onClick={() => setTab(t.code)}>
              {t.name}
            </button>
          ))}
        </div>
        {tab === 'coverages' && (
          <DataTable rowKey={(r) => r.cvrgCd} rows={c.coverages} columns={[
            { key: 'cvrgCd', title: '담보코드', render: (r) => <span className="mono">{r.cvrgCd}</span> },
            { key: 'name', title: '담보명', render: (r) => coverageName(r.cvrgCd) },
            { key: 'isrdAmt', title: '가입금액', align: 'right', render: (r) => money(r.isrdAmt) },
            { key: 'prem', title: '보험료', align: 'right', render: (r) => money(r.prem) },
          ]} />
        )}
        {tab === 'payments' && (
          <>
            <ErrorBox error={payments.error} />
            <DataTable loading={payments.loading} rows={payments.data?.items} rowKey={(r) => r.paymentId} empty="납입 내역이 없습니다." columns={[
              { key: 'paidDt', title: '수납일시', render: (r) => dateTime(r.paidDt) },
              { key: 'payAmt', title: '금액', align: 'right', render: (r) => money(r.payAmt) },
              { key: 'payMethod', title: '방법', render: (r) => nameOf(PAY_METHODS, r.payMethod) },
              { key: 'empId', title: '처리자' },
            ]} />
          </>
        )}
        {tab === 'status' && (
          <>
            <ErrorBox error={statusHist.error} />
            <DataTable loading={statusHist.loading} rows={statusHist.data?.items} columns={[
              { key: 'chgDt', title: '변경일시', render: (r) => dateTime(r.chgDt) },
              { key: 'before', title: '변경 전', render: (r) => (r.beforeCtrStatCd ? nameOf(CTR_STAT, r.beforeCtrStatCd) : '(최초 접수)') },
              { key: 'after', title: '변경 후', render: (r) => <StatusBadge code={r.afterCtrStatCd} /> },
              { key: 'empId', title: '처리자' },
            ]} />
          </>
        )}
        {tab === 'claims' && <ContractClaims contractNo={c.contractNo} />}
        {tab === 'endorse' && (
          <>
            <ErrorBox error={endorseHist.error} />
            <DataTable loading={endorseHist.loading} rows={endorseHist.data?.items} empty="배서 이력이 없습니다." columns={[
              { key: 'chgDt', title: '변경일시', render: (r) => dateTime(r.chgDt) },
              { key: 'fieldNm', title: '항목' },
              { key: 'beforeVal', title: '변경 전' },
              { key: 'afterVal', title: '변경 후' },
              { key: 'empId', title: '처리자' },
            ]} />
          </>
        )}
      </Panel>

      {modal?.type === 'status' && <StatusChangeModal contract={c} target={modal.target} onClose={() => setModal(null)} onDone={done} />}
      {modal?.type === 'pay' && <PaymentModal contract={c} remaining={remaining} onClose={() => setModal(null)} onDone={done} />}
      {modal?.type === 'endorse' && <EndorseModal contract={c} onClose={() => setModal(null)} onDone={done} />}
      {modal?.type === 'claim' && (
        <ClaimReportModal contract={c} onClose={() => setModal(null)}
                          onReported={(res) => { setModal(null); notify(`사고를 접수했습니다. (${res.clmNo})`); navigate(`/claims/${encodeURIComponent(res.clmNo)}`); }} />
      )}
      {modal?.type === 'renew' && (
        <RenewModal contract={c} onClose={() => setModal(null)}
                    onRenewed={(res) => {
                      setModal(null);
                      notify(`갱신 청약을 접수했습니다. (${res.contractNo})`);
                      navigate(`/contracts/${encodeURIComponent(res.contractNo)}`);
                    }} />
      )}
    </div>
  );
}

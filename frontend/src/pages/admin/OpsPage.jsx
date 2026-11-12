import { useState } from 'react';
import { Link } from 'react-router-dom';
import { batchApi, extApi, fepApi, payoutApi } from '../../api/endpoints';
import { useAuth } from '../../auth/AuthProvider';
import { BANKS, FEP_RESULTS, PAYOUT_STAT, RECON_RESULTS, nameOf } from '../../common/codes';
import { dateTime, money, seoulDate } from '../../common/format';
import { Badge, DataTable, ErrorBox, PageHeader, Panel, useLoad, useToast } from '../../components/ui';
import { PayoutStatusBadge } from '../claim/PayoutPanel';

// 대외계·지급 운영: FEP 기관 상태(개시·종료·회선 확인), 전문 로그, 지급 현황, 일일 대사(스프링 배치), 배치 이력, 신용정보원 전송 대기열.

const TABS = [
  { code: 'fep', name: 'FEP 기관' },
  { code: 'log', name: '전문 로그' },
  { code: 'payout', name: '지급 현황' },
  { code: 'recon', name: '지급 대사' },
  { code: 'batch', name: '배치 이력' },
  { code: 'outbox', name: '전송 대기열' },
];

function tone(list, code) {
  return list.find((x) => x.code === code)?.tone;
}

export function OpsPage() {
  const [tab, setTab] = useState('fep');
  return (
    <div className="page">
      <PageHeader title="대외계·지급 운영"
                  desc="대외기관 전문은 FEP 를 거쳐 나갑니다. 기관 점검 시간에는 종료해 두면 전문이 나가지 않고 대기열에 남았다가 개시 후 보내집니다." />
      <Panel>
        <div className="tabs" role="tablist">
          {TABS.map((t) => (
            <button key={t.code} type="button" role="tab" aria-selected={tab === t.code}
                    className={`tab ${tab === t.code ? 'active' : ''}`} onClick={() => setTab(t.code)}>{t.name}</button>
          ))}
        </div>
        {tab === 'fep' && <FepTab />}
        {tab === 'log' && <LogTab />}
        {tab === 'payout' && <PayoutTab />}
        {tab === 'recon' && <ReconTab />}
        {tab === 'batch' && <BatchTab />}
        {tab === 'outbox' && <OutboxTab />}
      </Panel>
    </div>
  );
}

function FepTab() {
  const { hasAny } = useAuth();
  const notify = useToast();
  const [version, setVersion] = useState(0);
  const [error, setError] = useState(null);
  const list = useLoad(() => fepApi.institutions(), [version]);
  const act = async (fn, message) => {
    setError(null);
    try {
      await fn();
      notify(message);
    } catch (err) {
      setError(err);
    } finally {
      setVersion((v) => v + 1);
    }
  };
  return (
    <>
      <ErrorBox error={error || list.error} onClose={() => setError(null)} />
      <DataTable rowKey={(r) => r.instCd} rows={list.data} loading={list.loading} columns={[
        { key: 'inst', title: '기관', render: (r) => <><strong>{r.instNm}</strong> <span className="mono muted">{r.instCd}</span></> },
        { key: 'signOn', title: '개시', render: (r) => <Badge tone={r.signOn ? 'ok' : 'muted'}>{r.signOn ? '개시' : '종료'}</Badge> },
        { key: 'circuit', title: '회선', render: (r) => (r.circuitOpen ? <Badge tone="danger">차단 ({r.consecutiveFailures}회)</Badge>
          : r.consecutiveFailures > 0 ? <Badge tone="warn">응답 없음 {r.consecutiveFailures}회</Badge> : <Badge tone="ok">정상</Badge>) },
        { key: 'echo', title: '마지막 회선 확인', render: (r) => (r.lastEchoDt ? <>{dateTime(r.lastEchoDt)} {r.lastEchoOk ? '성공' : '실패'}</> : '-') },
        { key: 'today', title: '오늘 전문', align: 'right', render: (r) => `${r.todayCount} (오류 ${r.todayErrors}, 응답 없음 ${r.todayTimeouts}, 차단 ${r.todayBlocked})` },
        { key: 'avg', title: '평균 응답', align: 'right', render: (r) => (r.todayAvgMs === null ? '-' : `${r.todayAvgMs} ms`) },
        { key: 'actions', title: '', render: (r) => (
          <div className="inline-form">
            <button type="button" className="btn" onClick={() => act(() => fepApi.echo(r.instCd), `${r.instNm} 회선을 확인했습니다.`)}>회선 확인</button>
            {hasAny('ADMIN') && (r.signOn
              ? <button type="button" className="btn btn-danger-ghost" onClick={() => act(() => fepApi.signOff(r.instCd), `${r.instNm} 종료`)}>종료</button>
              : <button type="button" className="btn btn-primary" onClick={() => act(() => fepApi.signOn(r.instCd), `${r.instNm} 개시`)}>개시</button>)}
          </div>
        ) },
      ]} />
      <p className="muted small">회선 차단은 응답 없음이 연속으로 이어지면 잠시 전문을 보내지 않는 장치입니다(서버마다 따로 셉니다). 회선 확인이 성공하면 풀립니다.</p>
    </>
  );
}

function LogTab() {
  const [cond, setCond] = useState({ instCd: '', resultCd: '', traceNo: '' });
  const [query, setQuery] = useState(cond);
  const list = useLoad(() => fepApi.messages({ instCd: query.instCd || null, resultCd: query.resultCd || null, traceNo: query.traceNo || null, limit: 100 }), [query]);
  return (
    <>
      <form className="search-bar" onSubmit={(e) => { e.preventDefault(); setQuery(cond); }}>
        <select value={cond.instCd} onChange={(e) => setCond({ ...cond, instCd: e.target.value })}>
          <option value="">전체 기관</option><option value="KCIS">KCIS</option><option value="BANK">BANK</option>
        </select>
        <select value={cond.resultCd} onChange={(e) => setCond({ ...cond, resultCd: e.target.value })}>
          <option value="">전체 결과</option>{FEP_RESULTS.map((r) => <option key={r.code} value={r.code}>{r.name}</option>)}
        </select>
        <input placeholder="추적번호" value={cond.traceNo} onChange={(e) => setCond({ ...cond, traceNo: e.target.value.trim() })} />
        <div className="search-actions"><button type="submit" className="btn btn-primary">조회</button></div>
      </form>
      <ErrorBox error={list.error} />
      <DataTable rowKey={(r) => r.logId} rows={list.data} loading={list.loading} columns={[
        { key: 'reqDt', title: '요청 일시', render: (r) => dateTime(r.reqDt) },
        { key: 'instCd', title: '기관' },
        { key: 'txType', title: '거래', render: (r) => <span className="mono">{r.txType}</span> },
        { key: 'traceNo', title: '추적번호', render: (r) => <span className="mono small">{r.traceNo}</span> },
        { key: 'resultCd', title: '결과', render: (r) => <Badge tone={tone(FEP_RESULTS, r.resultCd)}>{nameOf(FEP_RESULTS, r.resultCd)}</Badge> },
        { key: 'responseCd', title: '응답코드', render: (r) => r.responseCd ?? '-' },
        { key: 'elapsedMs', title: '시간', align: 'right', render: (r) => `${r.elapsedMs} ms` },
        { key: 'bytes', title: '바이트', align: 'right', render: (r) => `${r.reqBytes} / ${r.resBytes ?? '-'}` },
        { key: 'errorMsg', title: '오류', render: (r) => <span className="wrap small">{r.errorMsg ?? ''}</span> },
      ]} />
      <p className="muted small">전문 본문에는 계좌번호·이름 같은 개인정보가 있어 남기지 않고, 길이와 SHA-256 만 남깁니다.</p>
    </>
  );
}

function PayoutTab() {
  const [statCd, setStatCd] = useState('70');
  const list = useLoad(() => payoutApi.list({ statCd: statCd || null, limit: 100 }), [statCd]);
  return (
    <>
      <div className="search-bar">
        <select value={statCd} onChange={(e) => setStatCd(e.target.value)}>
          <option value="">전체 상태</option>{PAYOUT_STAT.map((s) => <option key={s.code} value={s.code}>{s.name}</option>)}
        </select>
      </div>
      <ErrorBox error={list.error} />
      <DataTable rowKey={(r) => r.payoutId} rows={list.data} loading={list.loading} empty="해당 상태의 지급이 없습니다." columns={[
        { key: 'statCd', title: '상태', render: (r) => <PayoutStatusBadge code={r.statCd} /> },
        { key: 'src', title: '원천', render: (r) => (r.srcType === 'CLAIM'
          ? <Link className="mono" to={`/claims/${encodeURIComponent(r.srcNo)}`}>{r.srcNo}</Link>
          : <Link className="mono" to={`/contracts/${encodeURIComponent(r.srcNo)}`}>마일리지 {r.srcNo}</Link>) },
        { key: 'payAmt', title: '금액', align: 'right', render: (r) => money(r.payAmt) },
        { key: 'account', title: '계좌', render: (r) => <>{nameOf(BANKS, r.bankCd)} <span className="mono">{r.accountMask}</span></> },
        { key: 'counts', title: '전송/조회', align: 'right', render: (r) => `${r.sendCount}/${r.inquiryCount}` },
        { key: 'nextTryDt', title: '다음 처리', render: (r) => (['10', '70'].includes(r.statCd) ? dateTime(r.nextTryDt) : '-') },
        { key: 'resultMsg', title: '결과', render: (r) => <span className="wrap small">{r.resultMsg ?? '-'}</span> },
      ]} />
    </>
  );
}

function ReconTab() {
  const { hasAny } = useAuth();
  const notify = useToast();
  const [reconDate, setReconDate] = useState(seoulDate(-1));
  const [diffOnly, setDiffOnly] = useState(false);
  const [version, setVersion] = useState(0);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);
  const list = useLoad(() => batchApi.payoutReconList(reconDate, diffOnly), [reconDate, diffOnly, version]);
  const run = async () => {
    setBusy(true);
    setError(null);
    try {
      const res = await batchApi.payoutRecon(reconDate);
      notify(`대사 배치 ${res.status} (실행 ${res.executionId})`, res.status === 'COMPLETED' ? 'ok' : 'danger');
      setVersion((v) => v + 1);
    } catch (err) {
      setError(err);
    } finally {
      setBusy(false);
    }
  };
  const counts = (list.data ?? []).reduce((acc, r) => ({ ...acc, [r.resultCd]: (acc[r.resultCd] ?? 0) + 1 }), {});
  return (
    <>
      <div className="inline-form">
        <input type="date" value={reconDate} max={seoulDate()} onChange={(e) => setReconDate(e.target.value)} />
        <label className="check"><input type="checkbox" checked={diffOnly} onChange={(e) => setDiffOnly(e.target.checked)} /> 차이 건만</label>
        {hasAny('OPERATOR', 'ADMIN') && <button type="button" id="recon-run" className="btn btn-primary" disabled={busy} onClick={run}>{busy ? '대사 중…' : '대사 실행'}</button>}
      </div>
      <p className="muted small">매일 06:30 에 전날 대사가 자동으로 돕니다. 같은 날도 다시 실행할 수 있습니다(은행 내역이 늦게 온 경우 등).
        {' '}{RECON_RESULTS.map((r) => `${r.name} ${counts[r.code] ?? 0}`).join(' · ')}</p>
      <ErrorBox error={error || list.error} onClose={() => setError(null)} />
      <DataTable rowKey={(r) => r.traceNo} rows={list.data} loading={list.loading} empty="대사 결과가 없습니다." columns={[
        { key: 'resultCd', title: '결과', render: (r) => <Badge tone={tone(RECON_RESULTS, r.resultCd)}>{nameOf(RECON_RESULTS, r.resultCd)}</Badge> },
        { key: 'traceNo', title: '거래고유번호', render: (r) => <span className="mono small">{r.traceNo}</span> },
        { key: 'src', title: '원천', render: (r) => (r.srcNo ? `${r.srcType === 'CLAIM' ? '보험금' : '환급금'} ${r.srcNo}` : '-') },
        { key: 'ourStatCd', title: '우리 상태', render: (r) => (r.ourStatCd ? <PayoutStatusBadge code={r.ourStatCd} /> : '-') },
        { key: 'ourAmt', title: '우리 금액', align: 'right', render: (r) => (r.ourAmt === null ? '-' : money(r.ourAmt)) },
        { key: 'bankAmt', title: '은행 금액', align: 'right', render: (r) => (r.bankAmt === null ? '-' : money(r.bankAmt)) },
      ]} />
    </>
  );
}

function BatchTab() {
  const [jobName, setJobName] = useState('');
  const list = useLoad(() => batchApi.executions(jobName || null, 30), [jobName]);
  return (
    <>
      <div className="search-bar">
        <select value={jobName} onChange={(e) => setJobName(e.target.value)}>
          <option value="">전체 작업</option>
          <option value="reserveCloseJob">지급준비금 결산</option>
          <option value="payoutReconJob">지급 대사</option>
        </select>
      </div>
      <ErrorBox error={list.error} />
      <DataTable rowKey={(r) => r.executionId} rows={list.data} loading={list.loading} empty="실행 이력이 없습니다." columns={[
        { key: 'executionId', title: '실행', align: 'right' },
        { key: 'jobName', title: '작업', render: (r) => (r.jobName === 'reserveCloseJob' ? '지급준비금 결산' : r.jobName === 'payoutReconJob' ? '지급 대사' : r.jobName) },
        { key: 'params', title: '파라미터', render: (r) => <span className="mono small">{r.params}</span> },
        { key: 'status', title: '상태', render: (r) => <Badge tone={r.status === 'COMPLETED' ? 'ok' : r.status === 'FAILED' ? 'danger' : 'info'}>{r.status}</Badge> },
        { key: 'time', title: '시작 ~ 종료', render: (r) => `${dateTime(r.startTime)} ~ ${dateTime(r.endTime)}` },
        { key: 'steps', title: '단계 (읽기/쓰기/걸러냄)', render: (r) => r.steps.map((s) => `${s.stepName} ${s.readCount}/${s.writeCount}/${s.filterCount}`).join(', ') },
        { key: 'exitMessage', title: '메시지', render: (r) => <span className="wrap small">{r.exitMessage ?? ''}</span> },
      ]} />
    </>
  );
}

function OutboxTab() {
  const { hasAny } = useAuth();
  const notify = useToast();
  const [statCd, setStatCd] = useState('');
  const [version, setVersion] = useState(0);
  const [error, setError] = useState(null);
  const list = useLoad(() => extApi.outboxList({ statCd: statCd || null, limit: 100 }), [statCd, version]);
  const retry = async (id) => {
    setError(null);
    try {
      await extApi.outboxRetry(id);
      notify('다시 보내도록 대기로 돌렸습니다.');
    } catch (err) {
      setError(err);
    } finally {
      setVersion((v) => v + 1);
    }
  };
  return (
    <>
      <div className="search-bar">
        <select value={statCd} onChange={(e) => setStatCd(e.target.value)}>
          <option value="">전체 상태</option><option value="10">전송 대기</option><option value="20">전송 완료</option><option value="90">멈춤 (확인 필요)</option>
        </select>
      </div>
      <ErrorBox error={error || list.error} onClose={() => setError(null)} />
      <DataTable rowKey={(r) => r.outboxId} rows={list.data} loading={list.loading} empty="대기열이 비어 있습니다." columns={[
        { key: 'outboxId', title: '번호', align: 'right' },
        { key: 'txType', title: '거래', render: (r) => `${r.agencyCd} ${r.txType}` },
        { key: 'bizKey', title: '업무 키', render: (r) => <span className="mono small">{r.bizKey}</span> },
        { key: 'statCd', title: '상태', render: (r) => <Badge tone={r.statCd === '20' ? 'ok' : r.statCd === '90' ? 'danger' : 'info'}>{r.statCd === '20' ? '완료' : r.statCd === '90' ? '멈춤' : '대기'}</Badge> },
        { key: 'attempts', title: '시도', align: 'right' },
        { key: 'lastError', title: '마지막 오류', render: (r) => <span className="wrap small">{r.lastError ?? ''}</span> },
        { key: 'createdDt', title: '쌓은 일시', render: (r) => dateTime(r.createdDt) },
        { key: 'act', title: '', render: (r) => (r.statCd === '90' && hasAny('ADMIN')
          ? <button type="button" className="btn" onClick={() => retry(r.outboxId)}>다시 보내기</button> : null) },
      ]} />
    </>
  );
}

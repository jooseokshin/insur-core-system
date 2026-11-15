import { useState } from 'react';
import { contractApi } from '../../api/endpoints';
import { useAuth } from '../../auth/AuthProvider';
import { MED_CHECK_STAT, nameOf } from '../../common/codes';
import { date, dateTime } from '../../common/format';
import { Badge, DataTable, ErrorBox, InfoGrid, Panel } from '../../components/ui';

// 실손 계약의 신용정보 조회 동의와 중복가입 조회 결과 (한국신용정보원).
// 청약 때 기관 장애로 조회하지 못하면(80) 승인이 막히므로, 심사·운영 담당자가 여기서 다시 조회한다.

export function MedicalCheckPanel({ contract, onDone }) {
  const { hasAny } = useAuth();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);
  const [others, setOthers] = useState(null);
  const m = contract.medicalCheck;
  if (!m) return null;
  const stat = MED_CHECK_STAT.find((s) => s.code === m.checkStatCd);
  const canRecheck = contract.ctrStatCd === '10' && hasAny('UNDERWRITER', 'OPERATOR', 'ADMIN');

  const recheck = async () => {
    setBusy(true);
    setError(null);
    try {
      const res = await contractApi.medicalDupCheck(contract.contractNo);
      setOthers(res.otherContracts);
      onDone(`중복가입을 다시 조회했습니다. (다른 보험사 실손 ${res.otherCount}건)`);
    } catch (err) {
      setError(err);
    } finally {
      setBusy(false);
    }
  };

  return (
    <Panel title="실손 중복가입 조회 (신용정보원)" actions={canRecheck && (
      <button type="button" id="medical-recheck" className="btn" onClick={recheck} disabled={busy}>{busy ? '조회 중…' : '다시 조회'}</button>
    )}>
      {m.checkStatCd === '80' && (
        <div className="alert alert-warn">
          신용정보원 연계 장애로 중복가입을 조회하지 못했습니다. 다시 조회해 성공해야 승인할 수 있습니다.
          {m.lastError && <> (사유: {m.lastError})</>}
        </div>
      )}
      {m.otherCount > 0 && (
        <div className="alert alert-info">
          다른 보험사 실손이 {m.otherCount}건 있습니다. 실손은 여러 개 가입해도 실제 의료비 안에서 나눠 보상(비례보상)하므로 더 받지 못한다는 점을 계약자에게 설명해야 합니다.
        </div>
      )}
      <ErrorBox error={error} onClose={() => setError(null)} />
      <InfoGrid items={[
        ['조회 상태', <Badge key="s" tone={stat?.tone}>{nameOf(MED_CHECK_STAT, m.checkStatCd)}</Badge>],
        ['다른 보험사 실손', m.otherCount == null ? '-' : `${m.otherCount}건`],
        ['신용정보 조회 동의', dateTime(m.consentDt)],
        ['마지막 조회', m.checkedDt ? `${dateTime(m.checkedDt)} (${m.checkEmpId ?? '-'})` : '-'],
      ]} />
      {others?.length > 0 && (
        <DataTable rowKey={(r) => `${r.insurerCd}-${r.policyNo}`} rows={others} columns={[
          { key: 'insurerCd', title: '보험회사', render: (r) => <span className="mono">{r.insurerCd}</span> },
          { key: 'policyNo', title: '증권번호 (마스킹)', render: (r) => <span className="mono">{r.policyNo}</span> },
          { key: 'kindCd', title: '구분', render: (r) => (r.kindCd === '02' ? '단체' : '개인') },
          { key: 'period', title: '보장 기간', render: (r) => `${date(r.startDate)} ~ ${date(r.endDate)}` },
          { key: 'scope', title: '보장', render: (r) => [r.covered && '급여', r.nonCovered && '비급여'].filter(Boolean).join('·') },
        ]} />
      )}
    </Panel>
  );
}

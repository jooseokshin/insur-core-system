import { useState } from 'react';
import { contractApi } from '../../api/endpoints';
import { newTxId } from '../../api/http';
import { useAuth } from '../../auth/AuthProvider';
import { date, dateTime, money, parseAmount, seoulDate } from '../../common/format';
import { Badge, ErrorBox, Field, InfoGrid, Modal, Panel, useLoad } from '../../components/ui';

// 자동차 계약의 마일리지 특약. 시작 주행거리를 등록하고 만기 무렵 만기 주행거리로 정산한다. 계산과 기간 규칙은 서버가 정한다.

const STATUS = { '10': { name: '시작 등록', tone: 'info' }, '20': { name: '정산 완료', tone: 'ok' } };

export function MileagePanel({ contract, version, onDone }) {
  const { hasAny } = useAuth();
  const [mode, setMode] = useState(null);
  const mileage = useLoad(() => contractApi.mileageInquiry(contract.contractNo), [contract.contractNo, version]);
  const m = mileage.data;
  const canEdit = hasAny('AGENT', 'OPERATOR', 'ADMIN');
  // 버튼은 서버 규칙과 같은 기간에만 보인다 (날짜는 업무 시간대 기준 'YYYY-MM-DD' 문자열이라 그대로 비교된다)
  const today = seoulDate();
  const registerable = m && !m.milStatCd && ['10', '20'].includes(contract.ctrStatCd) && today <= m.registerUntil;
  const settleable = m?.milStatCd === '10' && contract.ctrStatCd === '20' && today >= m.settleFrom && today <= m.settleUntil;

  return (
    <Panel title="마일리지 특약" actions={canEdit && m && (
      <>
        {registerable && <button type="button" id="mileage-register" className="btn" onClick={() => setMode('register')}>시작 주행거리 등록</button>}
        {settleable && <button type="button" id="mileage-settle" className="btn btn-primary" onClick={() => setMode('settle')}>만기 정산</button>}
      </>
    )}>
      <ErrorBox error={mileage.error} />
      {m && !m.milStatCd && (
        <p className="muted">
          가입하지 않았습니다. {today <= m.registerUntil
            ? `시작 주행거리는 ${date(m.registerUntil)}까지 등록할 수 있습니다.`
            : `등록 기간(${date(m.registerUntil)}까지)이 지났습니다.`}
        </p>
      )}
      {m?.milStatCd && (
        <InfoGrid items={[
          ['상태', <Badge key="s" tone={STATUS[m.milStatCd].tone}>{STATUS[m.milStatCd].name}</Badge>],
          ['시작 주행거리', `${m.startOdometerKm.toLocaleString()} km (${dateTime(m.startRegDt)})`],
          ['정산 가능 기간', `${date(m.settleFrom)} ~ ${date(m.settleUntil)}`],
          ...(m.milStatCd === '20' ? [
            ['만기 주행거리', `${m.endOdometerKm.toLocaleString()} km (${dateTime(m.endRegDt)})`],
            ['주행거리', `${m.drivenKm.toLocaleString()} km (1년 환산 ${m.annualKm.toLocaleString()} km)`],
            ['환급률', `${(Number(m.refundRate) * 100).toFixed(2)}%`],
            ['환급액', <strong key="r">{money(m.refundAmt)}원 (기준 보험료 {money(m.refundBasePrem)}원)</strong>],
          ] : []),
        ]} />
      )}
      {mode && (
        <MileageModal contract={contract} mileage={m} mode={mode} onClose={() => setMode(null)}
                      onDone={(message) => { setMode(null); onDone(message); }} />
      )}
    </Panel>
  );
}

function MileageModal({ contract, mileage, mode, onClose, onDone }) {
  // 대화상자를 열 때 거래 UUID 를 한 번 만들고 재시도에도 같은 값을 써서 두 번 반영되지 않게 한다
  const [txId] = useState(newTxId);
  const [odometer, setOdometer] = useState('');
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);
  const settle = mode === 'settle';
  const km = parseAmount(odometer);

  const submit = async () => {
    setBusy(true);
    setError(null);
    try {
      const res = settle
        ? await contractApi.mileageSettle(contract.contractNo, km, txId)
        : await contractApi.mileageRegister(contract.contractNo, km, txId);
      onDone(settle ? `정산했습니다. 환급액 ${money(res.refundAmt)}원` : '시작 주행거리를 등록했습니다.');
    } catch (err) {
      setError(err);
    } finally {
      setBusy(false);
    }
  };

  return (
    <Modal title={settle ? '마일리지 만기 정산' : '마일리지 시작 주행거리 등록'} onClose={onClose}
           footer={(
             <>
               <button type="button" className="btn" onClick={onClose}>닫기</button>
               <button type="button" id="mileage-confirm" className="btn btn-primary" disabled={busy || km === null || Number.isNaN(km)}
                       onClick={submit}>{busy ? '처리 중…' : (settle ? '정산' : '등록')}</button>
             </>
           )}>
      <p className="muted">
        {settle
          ? `계기판 누적 주행거리를 넣으면 시작(${mileage.startOdometerKm.toLocaleString()} km)과의 차이를 1년 기준으로 환산해 환급률을 정합니다.`
          : '계약 시작 무렵 계기판 누적 주행거리를 넣습니다. 계약당 한 번만 등록할 수 있습니다.'}
      </p>
      <Field label={settle ? '만기 주행거리 (km)' : '시작 주행거리 (km)'} required>
        <input id="mileage-odometer" className="num-input" inputMode="numeric" value={odometer} autoFocus
               onChange={(e) => setOdometer(e.target.value)} />
      </Field>
      <ErrorBox error={error} />
    </Modal>
  );
}

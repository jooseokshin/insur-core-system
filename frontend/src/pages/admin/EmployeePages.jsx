import { useState } from 'react';
import { employeeApi } from '../../api/endpoints';
import { newTxId } from '../../api/http';
import { useAuth } from '../../auth/AuthProvider';
import { LOGIN_RESULTS, ROLES, nameOf } from '../../common/codes';
import { dateTime } from '../../common/format';
import { Badge, DataTable, ErrorBox, Field, InfoGrid, Modal, PageHeader, Pagination, Panel, Select, useLoad, useToast } from '../../components/ui';

// 사원·권한 관리(인증 서버 /auth/admin/**). ADMIN 만 쓴다.
// 역할·사용 여부 변경은 이미 발급된 토큰에는 반영되지 않고, 해당 사원이 다음에 로그인할 때부터 적용된다.

const AUDIT_ACTIONS = { CREATE: '등록', UPDATE: '정보 변경', RESET_PASSWORD: '비밀번호 초기화', UNLOCK: '잠금 해제', CHANGE_PASSWORD: '본인 비밀번호 변경' };

function RoleChecks({ value, onChange }) {
  return (
    <div className="role-checks">
      {ROLES.map((r) => (
        <label key={r.code} className="check">
          <input type="checkbox" checked={value.includes(r.code)}
                 onChange={(e) => onChange(e.target.checked ? [...value, r.code] : value.filter((x) => x !== r.code))} />
          <span><strong>{r.name}</strong> <small className="muted">{r.desc}</small></span>
        </label>
      ))}
    </div>
  );
}

function AccountState({ e }) {
  if (!e.active) return <Badge tone="muted">사용 중지</Badge>;
  if (e.locked) return <Badge tone="danger">잠김</Badge>;
  if (e.passwordChangeRequired) return <Badge tone="warn">비밀번호 변경 대기</Badge>;
  return <Badge tone="ok">정상</Badge>;
}

/** 임시 비밀번호는 한 번만 보여 준다 */
function TemporaryPassword({ result, onClose }) {
  const [copied, setCopied] = useState(false);
  const copy = async () => {
    try {
      await navigator.clipboard.writeText(result.temporaryPassword);
      setCopied(true);
    } catch {
      setCopied(false);
    }
  };
  return (
    <Modal title="임시 비밀번호 발급" onClose={onClose}
           footer={<button type="button" className="btn btn-primary" onClick={onClose}>확인</button>}>
      <p><strong>{result.employee.empNm}</strong>({result.employee.empId})의 임시 비밀번호입니다. 이 창을 닫으면 다시 볼 수 없습니다.</p>
      <div className="temp-password">
        <code id="temp-password">{result.temporaryPassword}</code>
        <button type="button" className="btn btn-sm" onClick={copy}>{copied ? '복사됨' : '복사'}</button>
      </div>
      <p className="muted">사원은 첫 로그인 때 비밀번호를 바꿔야 업무를 시작할 수 있습니다. 메신저나 메일 대신 직접 전달해 주세요.</p>
    </Modal>
  );
}

function EmployeeForm({ initial, isNew, onClose, onSaved }) {
  const [form, setForm] = useState(initial);
  const [txId] = useState(newTxId);
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);
  const valid = form.empId.trim() && form.empNm.trim() && form.roles.length > 0;

  const save = async () => {
    setBusy(true);
    setError(null);
    try {
      const body = { empId: form.empId.trim(), empNm: form.empNm.trim(), deptNm: form.deptNm.trim(), roles: form.roles };
      const res = isNew ? await employeeApi.create(body, txId) : await employeeApi.update({ ...body, active: form.active }, txId);
      onSaved(res);
    } catch (err) {
      setError(err);
    } finally {
      setBusy(false);
    }
  };

  return (
    <Modal title={isNew ? '사원 등록' : `사원 정보 변경 - ${initial.empId}`} onClose={onClose} width={600}
           footer={(
             <>
               <button type="button" className="btn" onClick={onClose}>닫기</button>
               <button type="button" className="btn btn-primary" id="emp-save" disabled={busy || !valid} onClick={save}>{busy ? '저장 중…' : '저장'}</button>
             </>
           )}>
      <div className="form-grid">
        <Field label="사번" required hint={isNew ? '영문·숫자·_.- 3~20자' : undefined}>
          <input id="emp-id" value={form.empId} disabled={!isNew} maxLength={20} onChange={(e) => setForm({ ...form, empId: e.target.value })} />
        </Field>
        <Field label="이름" required>
          <input id="emp-name" value={form.empNm} maxLength={50} onChange={(e) => setForm({ ...form, empNm: e.target.value })} />
        </Field>
        <Field label="부서">
          <input id="emp-dept" value={form.deptNm} maxLength={50} onChange={(e) => setForm({ ...form, deptNm: e.target.value })} />
        </Field>
        {!isNew && (
          <Field label="사용 여부" required>
            <Select value={form.active ? 'Y' : 'N'} onChange={(v) => setForm({ ...form, active: v === 'Y' })}
                    options={[{ code: 'Y', name: '사용' }, { code: 'N', name: '사용 중지' }]} />
          </Field>
        )}
      </div>
      <Field label="역할" required hint="역할 변경은 다음 로그인부터 적용됩니다." wide>
        <RoleChecks value={form.roles} onChange={(roles) => setForm({ ...form, roles })} />
      </Field>
      {isNew && <p className="muted">저장하면 임시 비밀번호가 발급됩니다.</p>}
      <ErrorBox error={error} />
    </Modal>
  );
}

function EmployeeDetail({ empId, onClose, onChanged, onEdit }) {
  const notify = useToast();
  const [version, setVersion] = useState(0);
  const [temp, setTemp] = useState(null);
  const [error, setError] = useState(null);
  const detail = useLoad(() => employeeApi.detail(empId), [empId, version]);
  const e = detail.data?.employee;

  const run = async (action) => {
    setError(null);
    try {
      if (action === 'reset') setTemp(await employeeApi.resetPassword(empId, newTxId()));
      else {
        await employeeApi.unlock(empId, newTxId());
        notify('잠금을 해제했습니다.');
      }
      setVersion((v) => v + 1);
      onChanged();
    } catch (err) {
      setError(err);
    }
  };

  return (
    <Modal title={`사원 상세 - ${empId}`} onClose={onClose} width={720}
           footer={e && (
             <>
               <button type="button" className="btn" onClick={() => onEdit(e)}>정보·역할 변경</button>
               {e.locked && <button type="button" className="btn" onClick={() => run('unlock')}>잠금 해제</button>}
               <button type="button" className="btn btn-danger-ghost" id="emp-reset" onClick={() => run('reset')}>비밀번호 초기화</button>
             </>
           )}>
      <ErrorBox error={detail.error ?? error} />
      {e && (
        <>
          <InfoGrid items={[
            ['이름', e.empNm], ['부서', e.deptNm], ['역할', e.roles.map((r) => nameOf(ROLES, r)).join(', ')],
            ['상태', <AccountState key="s" e={e} />], ['연속 로그인 실패', `${e.loginFailCnt}회`],
            ['마지막 로그인', dateTime(e.lastLoginDt)], ['비밀번호 변경일', dateTime(e.pwdChgDt)],
            ['등록', `${dateTime(e.regDt)} (${e.regEmpId ?? '-'})`], ['최종 변경', `${dateTime(e.updDt)} (${e.updEmpId ?? '-'})`],
          ]} />
          <h3 className="sub-title">관리 이력</h3>
          <DataTable rows={detail.data.audits} empty="관리 이력이 없습니다." columns={[
            { key: 'actDt', title: '일시', render: (a) => dateTime(a.actDt) },
            { key: 'action', title: '작업', render: (a) => AUDIT_ACTIONS[a.action] ?? a.action },
            { key: 'detail', title: '내용', render: (a) => <span className="wrap">{a.detail}</span> },
            { key: 'actEmpId', title: '처리자' },
          ]} />
        </>
      )}
      {temp && <TemporaryPassword result={temp} onClose={() => setTemp(null)} />}
    </Modal>
  );
}

export function EmployeePage() {
  const { profile } = useAuth();
  const notify = useToast();
  const [form, setForm] = useState({ keyword: '', roleCd: '', state: '' });
  const [cond, setCond] = useState(form);
  const [page, setPage] = useState(0);
  const [version, setVersion] = useState(0);
  const [selected, setSelected] = useState(null);
  const [editing, setEditing] = useState(null);
  const [temp, setTemp] = useState(null);

  const list = useLoad(() => employeeApi.list({
    keyword: cond.keyword.trim() || null,
    roleCd: cond.roleCd || null,
    active: cond.state === 'ACTIVE' ? true : cond.state === 'INACTIVE' ? false : null,
    lockedOnly: cond.state === 'LOCKED' ? true : null,
    page, size: 20,
  }), [cond, page, version]);

  const refresh = () => setVersion((v) => v + 1);

  return (
    <div className="page">
      <PageHeader title="사원·권한 관리" desc="사원 계정을 등록하고 역할을 부여합니다. 잠긴 계정의 해제와 비밀번호 초기화도 여기서 합니다."
                  actions={<button type="button" className="btn btn-primary" id="emp-new"
                                   onClick={() => setEditing({ isNew: true, initial: { empId: '', empNm: '', deptNm: '', roles: [], active: true } })}>사원 등록</button>} />
      <Panel>
        <form className="search-bar" onSubmit={(ev) => { ev.preventDefault(); setPage(0); setCond(form); }}>
          <Field label="사번·이름"><input id="emp-keyword" value={form.keyword} onChange={(ev) => setForm({ ...form, keyword: ev.target.value })} /></Field>
          <Field label="역할"><Select value={form.roleCd} onChange={(v) => setForm({ ...form, roleCd: v })} options={ROLES} placeholder="전체" /></Field>
          <Field label="상태">
            <Select value={form.state} onChange={(v) => setForm({ ...form, state: v })} placeholder="전체"
                    options={[{ code: 'ACTIVE', name: '사용' }, { code: 'INACTIVE', name: '사용 중지' }, { code: 'LOCKED', name: '잠김' }]} />
          </Field>
          <div className="search-actions"><button type="submit" className="btn btn-primary">조회</button></div>
        </form>
      </Panel>
      <Panel>
        <ErrorBox error={list.error} />
        <DataTable loading={list.loading} rows={list.data?.items} rowKey={(r) => r.empId} onRowClick={(r) => setSelected(r.empId)} columns={[
          { key: 'empId', title: '사번', render: (r) => <span className="mono">{r.empId}</span> },
          { key: 'empNm', title: '이름', render: (r) => <>{r.empNm}{r.empId === profile.empId && <small className="muted"> (나)</small>}</> },
          { key: 'deptNm', title: '부서' },
          { key: 'roles', title: '역할', render: (r) => r.roles.map((x) => nameOf(ROLES, x)).join(', ') },
          { key: 'state', title: '상태', render: (r) => <AccountState e={r} /> },
          { key: 'lastLoginDt', title: '마지막 로그인', render: (r) => dateTime(r.lastLoginDt) },
        ]} />
        {list.data && <Pagination page={list.data.page} totalPages={list.data.totalPages} totalElements={list.data.totalElements} onChange={setPage} />}
      </Panel>

      {selected && (
        <EmployeeDetail empId={selected} onClose={() => setSelected(null)} onChanged={refresh}
                        onEdit={(e) => setEditing({ isNew: false, initial: { ...e, deptNm: e.deptNm ?? '' } })} />
      )}
      {editing && (
        <EmployeeForm initial={editing.initial} isNew={editing.isNew} onClose={() => setEditing(null)}
                      onSaved={(res) => {
                        setEditing(null);
                        refresh();
                        if (editing.isNew) setTemp(res);
                        else {
                          notify('사원 정보를 변경했습니다. 역할 변경은 다음 로그인부터 적용됩니다.');
                          setSelected(null);
                        }
                      }} />
      )}
      {temp && <TemporaryPassword result={temp} onClose={() => setTemp(null)} />}
    </div>
  );
}

export function LoginHistoryPage() {
  const [empId, setEmpId] = useState('');
  const [cond, setCond] = useState('');
  const [page, setPage] = useState(0);
  const { data, error, loading } = useLoad(() => employeeApi.loginHistory({ empId: cond || null, page, size: 30 }), [cond, page]);

  return (
    <div className="page">
      <PageHeader title="로그인 이력" desc="모든 로그인 시도(성공·실패)와 접속 IP 입니다. 없는 사번으로 시도한 것도 남습니다." />
      <Panel>
        <form className="search-bar" onSubmit={(e) => { e.preventDefault(); setPage(0); setCond(empId.trim()); }}>
          <Field label="사번"><input value={empId} onChange={(e) => setEmpId(e.target.value)} placeholder="전체" /></Field>
          <div className="search-actions"><button type="submit" className="btn btn-primary">조회</button></div>
        </form>
      </Panel>
      <Panel>
        <ErrorBox error={error} />
        <DataTable loading={loading} rows={data?.items} columns={[
          { key: 'loginDt', title: '시도 일시', render: (r) => dateTime(r.loginDt) },
          { key: 'empId', title: '사번', render: (r) => <span className="mono">{r.empId}</span> },
          { key: 'resultCd', title: '결과', render: (r) => <Badge tone={LOGIN_RESULTS.find((x) => x.code === r.resultCd)?.tone}>{nameOf(LOGIN_RESULTS, r.resultCd)}</Badge> },
          { key: 'clientIp', title: '접속 IP', render: (r) => <span className="mono">{r.clientIp ?? '-'}</span> },
        ]} />
        {data && <Pagination page={data.page} totalPages={data.totalPages} totalElements={data.totalElements} onChange={setPage} />}
      </Panel>
    </div>
  );
}

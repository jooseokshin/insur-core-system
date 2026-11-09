import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useAuth } from '../auth/AuthProvider';
import { ErrorBox, Field, Panel, useToast } from '../components/ui';

const RULES = [
  { text: '8~64자', test: (p) => p.length >= 8 && p.length <= 64 },
  { text: '영문 포함', test: (p) => /[A-Za-z]/.test(p) },
  { text: '숫자 포함', test: (p) => /\d/.test(p) },
  { text: '특수문자 포함', test: (p) => /[^A-Za-z0-9\s]/.test(p) },
  { text: '공백 없음', test: (p) => p.length > 0 && !/\s/.test(p) },
];

export function PasswordPage() {
  const { session, changePassword } = useAuth();
  const navigate = useNavigate();
  const notify = useToast();
  const [current, setCurrent] = useState('');
  const [next, setNext] = useState('');
  const [confirm, setConfirm] = useState('');
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);
  const containsEmpId = next && next.toLowerCase().includes(session.profile.empId.toLowerCase());
  const ready = RULES.every((r) => r.test(next)) && !containsEmpId && next === confirm && current;

  const submit = async (e) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await changePassword(current, next);
      notify('비밀번호를 변경했습니다. 다음 로그인부터 새 비밀번호를 씁니다.');
      navigate('/', { replace: true });
    } catch (err) {
      setError(err);
    } finally {
      setBusy(false);
    }
  };

  const form = (
    <form className="form-narrow" onSubmit={submit}>
      <ErrorBox error={error} />
      <Field label="현재 비밀번호" required>
        <input id="pw-current" type="password" value={current} onChange={(e) => setCurrent(e.target.value)}
               autoComplete="current-password" />
      </Field>
      <Field label="새 비밀번호" required>
        <input id="pw-next" type="password" value={next} onChange={(e) => setNext(e.target.value)} autoComplete="new-password" />
      </Field>
      <ul className="rule-list">
        {RULES.map((r) => (
          <li key={r.text} className={r.test(next) ? 'ok' : ''}>{r.text}</li>
        ))}
        <li className={next && !containsEmpId ? 'ok' : ''}>사번 미포함</li>
      </ul>
      <Field label="새 비밀번호 확인" required>
        <input id="pw-confirm" type="password" value={confirm} onChange={(e) => setConfirm(e.target.value)}
               autoComplete="new-password" />
      </Field>
      {confirm && next !== confirm && <p className="text-danger">새 비밀번호가 서로 다릅니다.</p>}
      <div className="form-actions">
        <button type="button" className="btn" onClick={() => navigate(-1)}>취소</button>
        <button type="submit" className="btn btn-primary" disabled={!ready || busy}>
          {busy ? '변경 중…' : '비밀번호 변경'}
        </button>
      </div>
    </form>
  );

  return (
    <div className="page">
      <Panel title="비밀번호 변경">{form}</Panel>
    </div>
  );
}

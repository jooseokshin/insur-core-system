import { createContext, useCallback, useContext, useEffect, useRef, useState } from 'react';
import { CTR_STAT, nameOf } from '../common/codes';

/** 비동기 조회 훅. deps 가 바뀌면 다시 불러오고, 늦게 도착한 이전 응답은 버린다. */
export function useLoad(loader, deps) {
  const [state, setState] = useState({ data: null, error: null, loading: true });
  const seq = useRef(0);
  const reload = useCallback(() => {
    const mine = ++seq.current;
    setState((s) => ({ ...s, loading: true, error: null }));
    loader()
      .then((data) => mine === seq.current && setState({ data, error: null, loading: false }))
      .catch((error) => mine === seq.current && setState({ data: null, error, loading: false }));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, deps);
  useEffect(() => {
    reload();
  }, [reload]);
  return { ...state, reload };
}

export function PageHeader({ title, desc, actions }) {
  return (
    <div className="page-header">
      <div>
        <h1>{title}</h1>
        {desc && <p className="page-desc">{desc}</p>}
      </div>
      {actions && <div className="page-actions">{actions}</div>}
    </div>
  );
}

export function Panel({ title, actions, children, className = '' }) {
  return (
    <section className={`panel ${className}`}>
      {(title || actions) && (
        <div className="panel-head">
          {title && <h2>{title}</h2>}
          {actions && <div className="panel-actions">{actions}</div>}
        </div>
      )}
      <div className="panel-body">{children}</div>
    </section>
  );
}

/** 업무 오류 표시: [오류코드] 메시지 + 거래 UUID(문의용) */
export function ErrorBox({ error, onClose }) {
  if (!error) return null;
  return (
    <div className="alert alert-error" role="alert">
      <div>
        <strong>{error.code ? `[${error.code}] ` : ''}</strong>
        {error.message}
        {error.globalTxId && <div className="alert-sub">거래 ID {error.globalTxId}</div>}
      </div>
      {onClose && (
        <button type="button" className="alert-close" onClick={onClose} aria-label="닫기">
          ×
        </button>
      )}
    </div>
  );
}

export function Field({ label, required, hint, children, wide }) {
  return (
    <label className={`field ${wide ? 'field-wide' : ''}`}>
      <span className="field-label">
        {label}
        {required && <em className="req">*</em>}
      </span>
      {children}
      {hint && <span className="field-hint">{hint}</span>}
    </label>
  );
}

export function Select({ value, onChange, options, placeholder, ...rest }) {
  return (
    <select value={value ?? ''} onChange={(e) => onChange(e.target.value)} {...rest}>
      {placeholder !== undefined && <option value="">{placeholder}</option>}
      {options.map((o) => (
        <option key={o.code} value={o.code}>
          {o.name}
        </option>
      ))}
    </select>
  );
}

export function Badge({ tone = 'muted', children }) {
  return <span className={`badge badge-${tone}`}>{children}</span>;
}

export function StatusBadge({ code }) {
  const stat = CTR_STAT.find((s) => s.code === code);
  return <Badge tone={stat?.tone}>{nameOf(CTR_STAT, code)}</Badge>;
}

/**
 * 목록 표. columns: [{ key, title, render?(row), align?, width? }]
 */
export function DataTable({ columns, rows, onRowClick, empty = '조회된 자료가 없습니다.', loading, rowKey }) {
  return (
    <div className="table-wrap">
      <table className="grid">
        <thead>
          <tr>
            {columns.map((c) => (
              <th key={c.key} style={{ width: c.width, textAlign: c.align }}>
                {c.title}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {loading && (
            <tr>
              <td colSpan={columns.length} className="grid-empty">
                불러오는 중…
              </td>
            </tr>
          )}
          {!loading && (!rows || rows.length === 0) && (
            <tr>
              <td colSpan={columns.length} className="grid-empty">
                {empty}
              </td>
            </tr>
          )}
          {!loading &&
            rows?.map((row, i) => (
              <tr
                key={rowKey ? rowKey(row) : i}
                className={onRowClick ? 'clickable' : undefined}
                onClick={onRowClick ? () => onRowClick(row) : undefined}
                tabIndex={onRowClick ? 0 : undefined}
                onKeyDown={onRowClick ? (e) => e.key === 'Enter' && onRowClick(row) : undefined}
              >
                {columns.map((c) => (
                  <td key={c.key} style={{ textAlign: c.align }}>
                    {c.render ? c.render(row) : (row[c.key] ?? '-')}
                  </td>
                ))}
              </tr>
            ))}
        </tbody>
      </table>
    </div>
  );
}

export function Pagination({ page, totalPages, totalElements, onChange }) {
  if (totalElements === undefined) return null;
  const last = Math.max(totalPages - 1, 0);
  const start = Math.max(0, Math.min(page - 2, last - 4));
  const pages = [];
  for (let p = start; p <= Math.min(last, start + 4); p++) pages.push(p);
  return (
    <div className="pager">
      <span className="pager-total">전체 {totalElements.toLocaleString()}건</span>
      <div className="pager-buttons">
        <button type="button" disabled={page <= 0} onClick={() => onChange(0)} aria-label="첫 페이지">
          «
        </button>
        <button type="button" disabled={page <= 0} onClick={() => onChange(page - 1)} aria-label="이전 페이지">
          ‹
        </button>
        {pages.map((p) => (
          <button key={p} type="button" className={p === page ? 'current' : ''} onClick={() => onChange(p)}>
            {p + 1}
          </button>
        ))}
        <button type="button" disabled={page >= last} onClick={() => onChange(page + 1)} aria-label="다음 페이지">
          ›
        </button>
        <button type="button" disabled={page >= last} onClick={() => onChange(last)} aria-label="마지막 페이지">
          »
        </button>
      </div>
    </div>
  );
}

export function Modal({ title, onClose, children, footer, width = 520 }) {
  useEffect(() => {
    const onKey = (e) => e.key === 'Escape' && onClose();
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose]);
  return (
    <div className="modal-backdrop" onMouseDown={(e) => e.target === e.currentTarget && onClose()}>
      <div className="modal" style={{ maxWidth: width }} role="dialog" aria-modal="true" aria-label={title}>
        <div className="modal-head">
          <h2>{title}</h2>
          <button type="button" className="modal-x" onClick={onClose} aria-label="닫기">
            ×
          </button>
        </div>
        <div className="modal-body">{children}</div>
        {footer && <div className="modal-foot">{footer}</div>}
      </div>
    </div>
  );
}

/** 정의 목록(라벨: 값) 형태의 상세 정보 */
export function InfoGrid({ items }) {
  return (
    <dl className="info-grid">
      {items.map(([label, value]) => (
        <div key={label} className="info-item">
          <dt>{label}</dt>
          <dd>{value ?? '-'}</dd>
        </div>
      ))}
    </dl>
  );
}

const ToastContext = createContext(() => {});

export function ToastProvider({ children }) {
  const [toasts, setToasts] = useState([]);
  const notify = useCallback((text, tone = 'ok') => {
    const id = crypto.randomUUID();
    setToasts((list) => [...list, { id, text, tone }]);
    setTimeout(() => setToasts((list) => list.filter((t) => t.id !== id)), 3500);
  }, []);
  return (
    <ToastContext.Provider value={notify}>
      {children}
      <div className="toasts" aria-live="polite">
        {toasts.map((t) => (
          <div key={t.id} className={`toast toast-${t.tone}`}>
            {t.text}
          </div>
        ))}
      </div>
    </ToastContext.Provider>
  );
}

export function useToast() {
  return useContext(ToastContext);
}

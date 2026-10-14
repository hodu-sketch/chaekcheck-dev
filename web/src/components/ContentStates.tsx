import { Link, type To } from 'react-router'
import styles from './ContentStates.module.css'

/**
 * The states of a notice or document screen (cycle 16, spec 상태별 동작): grey lines while loading, "글을 불러오지
 * 못했어요" with 다시 시도 on a server error or a lost connection, "찾을 수 없어요" with a way back.
 */
export function LoadingLines({ lines = 4 }: { lines?: number }) {
  return (
    <div className={styles.loading} role="status" aria-label="불러오는 중">
      {Array.from({ length: lines }, (_, index) => (
        <span key={index} className={styles.line} />
      ))}
    </div>
  )
}

export function LoadFailed({ onRetry }: { onRetry: () => void }) {
  return (
    <div className={styles.box} role="alert">
      <p className={styles.text}>글을 불러오지 못했어요</p>
      <button type="button" className={styles.retry} onClick={onRetry}>
        다시 시도
      </button>
    </div>
  )
}

export function NotFound({ to, label, state }: { to: To; label: string; state?: unknown }) {
  return (
    <div className={styles.box}>
      <p className={styles.text}>찾을 수 없어요</p>
      <Link to={to} state={state} className={styles.link}>
        {label}
      </Link>
    </div>
  )
}

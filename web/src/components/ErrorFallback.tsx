import styles from './ErrorFallback.module.css'

interface Props {
  onRetry: () => void
  retryLabel?: string
}

/** Shown in place of a screen that failed to render. */
export function ErrorFallback({ onRetry, retryLabel = '다시 시도' }: Props) {
  return (
    <div className={styles.box} role="alert">
      <p className={styles.title}>문제가 생겼어요</p>
      <p className={styles.text}>화면을 그리는 중에 오류가 났어요. 다시 시도해도 안 되면 페이지를 새로고침해 주세요.</p>
      <button type="button" className={styles.retry} onClick={onRetry}>
        {retryLabel}
      </button>
    </div>
  )
}

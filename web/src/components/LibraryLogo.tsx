import { useState } from 'react'
import styles from './LibraryLogo.module.css'

interface Props {
  name: string
  /** `/library-logos/…` from the API, or null when the library has no logo of its own */
  logoUrl: string | null | undefined
  /** shown height in px: about 20 on result cards, 24 on 내 도서관 (cycle 07 flow) */
  size: 20 | 24
}

/** Only our own logo files are shown (ADR-0059); anything else is treated as no logo. */
function ownLogo(url: string | null | undefined): string | undefined {
  return url && /^\/library-logos\/[\w.-]+$/.test(url) ? url : undefined
}

/**
 * A library's own logo above its name (cycle 07, ADR-0059). No logo, or one that fails to load, shows nothing at all
 * (user request 2026-10-05: no empty box). A picture only: a click does nothing (user 2026-10-19: "도서관 로고 클릭
 * 이벤트 다 없애줘"; it opened the library's main page before).
 */
export function LibraryLogo({ name, logoUrl, size }: Props) {
  const [failed, setFailed] = useState(false)
  const src = failed ? undefined : ownLogo(logoUrl)
  if (!src) return null
  return (
    <span className={styles.wrap}>
      <img className={styles.logo} src={src} alt={name} height={size} style={{ height: size }} onError={() => setFailed(true)} />
    </span>
  )
}

import { useState } from 'react'
import { safeHttpUrl } from '../lib/safeUrl'
import styles from './LibraryLogo.module.css'

interface Props {
  name: string
  /** `/library-logos/…` from the API, or null when the library has no logo of its own */
  logoUrl: string | null | undefined
  /** the e-library's main page; the logo opens it in a new tab */
  homepageUrl: string | null | undefined
  /** shown height in px: about 20 on result cards, 24 on 내 도서관 (cycle 07 flow) */
  size: 20 | 24
}

/** Only our own logo files are shown (ADR-0059); anything else is treated as no logo. */
function ownLogo(url: string | null | undefined): string | undefined {
  return url && /^\/library-logos\/[\w.-]+$/.test(url) ? url : undefined
}

/**
 * A library's own logo before its name (cycle 07, ADR-0059). No logo, or one that fails to load, shows an empty box
 * of the same height. Clicking opens the library's main page in a new tab; on a result card it sits above the card
 * link, so it never opens the book.
 */
export function LibraryLogo({ name, logoUrl, homepageUrl, size }: Props) {
  const [failed, setFailed] = useState(false)
  const src = failed ? undefined : ownLogo(logoUrl)
  const href = safeHttpUrl(homepageUrl)
  const picture = src ? (
    <img className={styles.logo} src={src} alt={name} height={size} style={{ height: size }} onError={() => setFailed(true)} />
  ) : (
    <span className={styles.empty} style={{ width: size, height: size }} aria-hidden={href ? undefined : true} />
  )
  if (!href) return <span className={styles.wrap}>{picture}</span>
  return (
    <a className={`${styles.wrap} ${styles.link}`} href={href} target="_blank" rel="noopener noreferrer">
      {picture}
      <span className="visually-hidden">{src ? '' : name} 홈페이지 (새 창)</span>
    </a>
  )
}

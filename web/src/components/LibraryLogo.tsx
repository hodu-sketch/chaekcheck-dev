import { useState } from 'react'
import { libraryClick, type ClickContext } from '../lib/libraryClick'
import { useLibraryLink } from '../lib/libraryLink'
import { safeHttpUrl } from '../lib/safeUrl'
import styles from './LibraryLogo.module.css'

interface Props {
  name: string
  /** `/library-logos/…` from the API, or null when the library has no logo of its own */
  logoUrl: string | null | undefined
  /** the e-library's main page; the logo opens it in a new tab (the same tab on touch screens) */
  homepageUrl: string | null | undefined
  /** shown height in px: about 20 on result cards, 24 on 내 도서관 (cycle 07 flow) */
  size: 20 | 24
  /** For the usage report of a click (cycle 15): the library and where the logo is shown; none sends nothing. */
  libraryId?: number
  click?: ClickContext
}

/** Only our own logo files are shown (ADR-0059); anything else is treated as no logo. */
function ownLogo(url: string | null | undefined): string | undefined {
  return url && /^\/library-logos\/[\w.-]+$/.test(url) ? url : undefined
}

/**
 * A library's own logo above its name (cycle 07, ADR-0059). No logo, or one that fails to load, shows nothing at all
 * (user request 2026-10-05: no empty box). Clicking opens the library's main page in a new tab on a computer and in
 * the same tab on touch screens (user 2026-10-19).
 */
export function LibraryLogo({ name, logoUrl, homepageUrl, size, libraryId, click }: Props) {
  const [failed, setFailed] = useState(false)
  const src = failed ? undefined : ownLogo(logoUrl)
  const href = safeHttpUrl(homepageUrl)
  const link = useLibraryLink()
  if (!src) return null
  const picture = (
    <img className={styles.logo} src={src} alt={name} height={size} style={{ height: size }} onError={() => setFailed(true)} />
  )
  if (!href) return <span className={styles.wrap}>{picture}</span>
  return (
    <a
      className={`${styles.wrap} ${styles.link}`}
      href={href}
      {...link.props}
      {...libraryClick(libraryId, 'logo', click)}
    >
      {picture}
      <span className="visually-hidden"> 홈페이지{link.newTab && ' (새 창)'}</span>
    </a>
  )
}

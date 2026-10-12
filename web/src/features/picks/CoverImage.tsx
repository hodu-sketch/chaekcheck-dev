import { useState } from 'react'
import { cardTitle, safeCoverUrl } from './picksText'
import styles from './CoverImage.module.css'

type Props = {
  url: string | null
  title: string
  /** Size and corners come from the place it is shown in (grid card, S14). */
  className?: string
}

/**
 * The cover stored from the National Library of Korea (ADR-0091), lazily loaded; without a cover or when it does not
 * load, the main title in its place, cut to a few lines (R4-4, AC-14; cycle 14: the long subtitles of the picks do
 * not fit a card). Decorative for screen readers: the card or window around it names the book.
 */
export function CoverImage({ url, title, className }: Props) {
  const safe = safeCoverUrl(url)
  const [broken, setBroken] = useState(false)
  const frame = className ? `${styles.frame} ${className}` : styles.frame
  return (
    <span className={frame} data-cover="">
      {safe && !broken ? (
        <img className={styles.image} src={safe} alt="" loading="lazy" decoding="async" onError={() => setBroken(true)} />
      ) : (
        <span className={styles.title} data-testid="title-cover">
          <span className={styles.titleText}>{cardTitle(title)}</span>
        </span>
      )}
    </span>
  )
}

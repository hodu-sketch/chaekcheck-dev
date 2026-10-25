import type { ForYouCard as Card } from '../../api/types'
import { CoverImage } from './CoverImage'
import { listsFooter, myLibrariesText } from './forYouText'
import styles from './ForYouPage.module.css'

type Props = {
  card: Card
  /** the one card across the whole row at the top (R1-3) */
  wide?: boolean
  onPick: (card: Card) => void
}

/**
 * One card of 「책첵 추천」 (R3-1, R3-2, R9-5): cover (or the title in its place) on the left, then title, author ·
 * publisher, the tab blurb whole (never cut, AC-11) or, without one, the lists' AI summary (user 2026-10-25: "오른쪽에
 * 적당히 큰 글자로 ai요약"; spec R8-1 keeps the two apart, so the blurb wins when there is one), 2-3 tags that do nothing when pressed, "내 도서관 N곳", and the lists' footer for a
 * listed book. A tap opens S14. No AI mark on the card (AC-9).
 */
export function ForYouCard({ card, wide = false, onPick }: Props) {
  const meta = [card.author, card.publisher].filter(Boolean).join(' · ')
  const footer = listsFooter(card.lists)
  const words = card.blurb ?? card.aiSummary ?? null
  return (
    <li className={wide ? styles.topCell : styles.cell}>
      <button type="button" className={styles.card} data-wide={wide || undefined} onClick={() => onPick(card)}>
        <span className={styles.coverBox}>
          <CoverImage className={styles.cover} url={card.coverUrl} title={card.title} />
        </span>
        <span className={styles.text}>
          <span className={styles.title}>{card.title}</span>
          {meta && <span className={styles.meta}>{meta}</span>}
          {words && <span className={styles.blurb}>{words}</span>}
          {card.tags.length > 0 && (
            <span className={styles.tags}>
              {card.tags.map((tag) => (
                <span key={tag.code} className={styles.tag} data-axis={tag.axis}>
                  {tag.name}
                </span>
              ))}
            </span>
          )}
          <span className={styles.held}>{myLibrariesText(card.planLibraryCount)}</span>
          {footer && <span className={styles.footer}>{footer}</span>}
        </span>
      </button>
    </li>
  )
}

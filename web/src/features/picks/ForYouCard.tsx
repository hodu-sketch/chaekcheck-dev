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
 * One card of 「나에게 맞는 책」 (R3-1, R3-2, R9-5): cover (or the title in its place), title, author · publisher, the
 * blurb whole (never cut, AC-11), 2-3 tags that do nothing when pressed, "내 도서관 N곳", and the lists' footer for a
 * listed book. A tap opens S14. No AI mark on the card (AC-9).
 */
export function ForYouCard({ card, wide = false, onPick }: Props) {
  const meta = [card.author, card.publisher].filter(Boolean).join(' · ')
  const footer = listsFooter(card.lists)
  return (
    <li className={wide ? styles.topCell : styles.cell}>
      <button type="button" className={styles.card} data-wide={wide || undefined} onClick={() => onPick(card)}>
        <span className={styles.coverBox}>
          <CoverImage className={styles.cover} url={card.coverUrl} title={card.title} />
        </span>
        <span className={styles.text}>
          <span className={styles.title}>{card.title}</span>
          {meta && <span className={styles.meta}>{meta}</span>}
          {card.blurb && <span className={styles.blurb}>{card.blurb}</span>}
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

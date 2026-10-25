import type { ForYouCard as Card, PickBook } from '../../api/types'
import { PICKS_LISTS } from './picksLists'

/**
 * S14 takes a picks book; a for-you card is one with its plan library known to hold it. A listed book brings the
 * lists' AI summary and its year line's month or year as on its list's tab (user 2026-10-24: "24 뒤에 붙임").
 */
export function pickBook(card: Card): PickBook {
  return {
    id: String(card.bookId),
    category: 'for_you',
    title: card.title,
    listTitle: null,
    coverUrl: card.coverUrl,
    bookId: card.bookId,
    available: true,
    recommendedMonth: card.recommendedMonth ?? null,
    yearBook: card.yearBook ?? null,
    videoId: null,
    tocNo: null,
    aiSummary: card.aiSummary ?? null,
    fieldGroup: card.fieldGroup ?? null,
  }
}

/** S14's year line as the first of the card's lists that has one shows it (the 국립중앙도서관 month, KAIST's year). */
export function yearLineOf(card: Card): string | null {
  const book = pickBook(card)
  for (const key of card.lists) {
    const line = PICKS_LISTS[key]?.yearLine?.(book)
    if (line) return line
  }
  return null
}

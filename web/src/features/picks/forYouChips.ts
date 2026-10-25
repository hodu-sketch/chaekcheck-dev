import type { ForYouCard } from '../../api/types'
import type { ViewedBook } from './viewedBooks'

/**
 * The chips of 「책첵 추천」 (user 2026-10-25: "카이스트 추천과 같은 디자인으로 … 위에 분류도 쓰는데 좀 사용자기반으로
 * 다양하게", "사용자마다 나타나는 분류가 달라야 해"). Readers' names over the six field groups; a chip with no book in
 * this person's list is not shown, and the fields come in the order of the fields this browser looked at most.
 */
export interface ForYouChip {
  code: string
  name: string
}

export const ALL_CHIP: ForYouChip = { code: 'all', name: '전체' }
export const SIMILAR_CHIP: ForYouChip = { code: 'similar', name: '최근 본 책과 비슷한' }

/** [안] the field chips, in their order when nothing was looked at; `mind` has no source until books get tags */
export const FIELD_CHIPS: readonly (ForYouChip & { group: string })[] = [
  { code: 'literature', group: 'literature', name: '소설·에세이' },
  { code: 'mind', group: 'mind', name: '마음·자기계발' },
  { code: 'humanities', group: 'humanities', name: '역사·철학' },
  { code: 'society', group: 'society', name: '세상·경제' },
  { code: 'science', group: 'science', name: '과학·기술' },
  { code: 'arts', group: 'arts', name: '예술·취미' },
]

/**
 * The chips this person sees: 전체, then 최근 본 책과 비슷한 when there are personal picks, then the field chips that
 * have books, those of the fields looked at most first (ties and the rest in the default order).
 */
export function chipsFor(cards: ForYouCard[], personal: ReadonlySet<number>, viewed: ViewedBook[]): ForYouChip[] {
  const looked = new Map<string, number>()
  for (const book of viewed) {
    if (book.fieldGroup) looked.set(book.fieldGroup, (looked.get(book.fieldGroup) ?? 0) + 1)
  }
  const fields = FIELD_CHIPS.filter((chip) => cards.some((card) => card.fieldGroup === chip.group))
    .map((chip, index) => ({ chip, index, count: looked.get(chip.group) ?? 0 }))
    .sort((a, b) => b.count - a.count || a.index - b.index)
    .map(({ chip }) => ({ code: chip.code, name: chip.name }))
  const similar = cards.some((card) => personal.has(card.bookId)) ? [SIMILAR_CHIP] : []
  return [ALL_CHIP, ...similar, ...fields]
}

/** The cards under a chip, in the list's order. */
export function cardsFor(chip: string, cards: ForYouCard[], personal: ReadonlySet<number>): ForYouCard[] {
  if (chip === ALL_CHIP.code) return cards
  if (chip === SIMILAR_CHIP.code) return cards.filter((card) => personal.has(card.bookId))
  const field = FIELD_CHIPS.find((chip2) => chip2.code === chip)
  return field ? cards.filter((card) => card.fieldGroup === field.group) : cards
}

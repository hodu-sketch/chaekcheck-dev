import { useQuery } from '@tanstack/react-query'
import { useId, useMemo, useState } from 'react'
import { fetchSidoGuess } from '../../api/client'
import { pickExample, START_TEXT, type ExampleCard } from './SearchPage'
import styles from './SearchPage.module.css'

/** 거주 pills on a phone row (wider screens show all four). */
const PHONE_PILLS = 3

/** The 시도 ask waits this long at most; the card then shows a 서울 example. */
const GUESS_TIMEOUT_MS = 1500

/** Random numbers read in turn (the same picks on every render). */
function seeded(seeds: readonly number[]): () => number {
  let at = 0
  return () => seeds[at++ % seeds.length]
}

/**
 * `count` example regions of the visitor's 시도 by the address (user 2026-10-20), each another region where the 시도
 * has enough, fixed for the visit. Empty until the 시도 ask settles (at most 1.5 s), so a card never changes under the
 * reader. Since 2026-10-22 on the picks screen, between its books ("예시 카드는 추천도서에서 쓰일거잖아").
 */
export function useExampleCards(count: number): ExampleCard[] {
  const [seeds] = useState(() => Array.from({ length: 16 * Math.max(count, 1) }, () => Math.random()))
  const guess = useQuery({
    queryKey: ['sidoGuess'],
    queryFn: ({ signal }) => fetchSidoGuess(AbortSignal.any([signal, AbortSignal.timeout(GUESS_TIMEOUT_MS)])),
    staleTime: Infinity,
    retry: false,
  })
  const settled = guess.isSuccess || guess.isError
  const sido = guess.data?.sido ?? null
  return useMemo(() => {
    if (!settled) return []
    const random = seeded(seeds)
    const cards: ExampleCard[] = []
    // up to a few tries per card for a region not shown yet
    for (let tries = 0; cards.length < count && tries < count * 4; tries++) {
      const card = pickExample(sido, random)
      if (!cards.some((one) => one.id === card.id) || tries >= count * 3) cards.push(card)
    }
    return cards
  }, [settled, sido, seeds, count])
}

/**
 * The 「만약에 서울 강남구에 살면」 card (user 2026-10-20, ADR-0139 #2): 거주, 재직 「일하는 곳 +」 and 상관없이 rows, then
 * 「전자도서관에서 빌릴 수 있어요」. It was the first screen's card until 2026-10-22; now the picks screen shows it.
 */
export function ExampleCardView({
  phone,
  card,
  isExample = true,
  className,
}: {
  phone: boolean
  card: ExampleCard
  isExample?: boolean
  className?: string
}) {
  const titleId = useId()
  const residence = phone ? card.residence.slice(0, PHONE_PILLS) : card.residence
  const rest = (count: number, extra = 0, alone = false) => {
    const left = count + extra
    // a region whose residents join no library of their own (제주 today): say so
    if (left <= 0) return alone ? START_TEXT.none : null
    // no pill before it: just the count
    if (alone) return START_TEXT.only(left)
    return phone ? START_TEXT.restPhone(left) : START_TEXT.rest(left)
  }
  const placed = {
    tone: 'mine',
    pills: residence,
    rest: rest(card.residenceRest, card.residence.length - residence.length, residence.length === 0),
  }
  // 거주 wins over 직장 (user 2026-10-20: "둘 중엔 거주가 우위"); only a workplace: a 재직 card
  const workCard = card.basis === 'work'
  const rows = [
    workCard
      ? { key: START_TEXT.residence, tone: 'mine', pills: [START_TEXT.livePill], rest: START_TEXT.workRest }
      : { key: START_TEXT.residence, ...placed },
    // always 「일하는 곳 +」 on a 거주 card: a workplace brings more than its own 시군구's library (user 2026-10-20)
    workCard
      ? { key: START_TEXT.work, ...placed }
      : { key: START_TEXT.work, tone: 'mine', pills: [START_TEXT.workPill], rest: START_TEXT.workRest },
    { key: START_TEXT.open, tone: 'open', pills: card.open, rest: rest(card.openRest) },
  ]
  return (
    <aside className={className ? `${styles.steps} ${className}` : styles.steps} aria-labelledby={titleId}>
      <h2 id={titleId} className={styles.stepsTitle}>
        {/* smaller and in the foot's grey (user 2026-10-21) */}
        {isExample && <span className={styles.stepsTitleIf}>{START_TEXT.exampleIf} </span>}
        {START_TEXT.exampleTitle(card.place, card.basis)}
      </h2>
      <ul className={styles.exampleRows}>
        {rows.map((row) => (
          <li key={row.key} className={styles.exampleRow}>
            <span className={styles.exampleKey}>{row.key}</span>
            {row.pills.map((pill) => (
              <span key={pill} className={styles.examplePill} data-tone={row.tone}>
                {pill}
              </span>
            ))}
            {row.rest && <span className={styles.exampleRest}>{row.rest}</span>}
          </li>
        ))}
      </ul>
      {/* bottom right, small and soft (user 2026-10-20) */}
      <p className={styles.exampleFoot}>{START_TEXT.exampleFoot}</p>
    </aside>
  )
}

import { useId, useMemo, useState } from 'react'
import { useLocation, useNavigate, useSearchParams } from 'react-router'
import { useQuery } from '@tanstack/react-query'
import { fetchSidoGuess, type BookQuery } from '../../api/client'
import type { BookSort, ResultFilter, VisitFilter } from '../../api/types'
import { readFilter, writeFilter } from './resultFilter'
import { checkQuery, normalizeQuery } from '../../lib/query'
import { useMediaQuery } from '../../lib/useMediaQuery'
import { useRegions } from '../region/RegionContext'
import { shownPrefix, useRegionLabels, useRegionTree } from '../region/useRegionTree'
import { hasRegions } from '../region/regionStorage'
import { BookList } from './BookList'
import { DirectResults, NoSort } from './DirectResults'
import { InstantResults } from './InstantResults'
import { START_EXAMPLES, type StartExample as StartExampleData } from './startExamples'
import {
  LibraryScopeChip,
  LibraryScopeLine,
  LibraryScopeSheet,
  ScopeNote,
  type BoxFocus,
  type ScopeSearched,
} from './LibraryScopeLine'
import { EMPTY_INPUT, filledFields, readBookSearch, readCondition, writeBookSearch, type BookSearchInput } from './searchCondition'
import { SearchForm } from './SearchForm'
import { readScope } from './readScope'
import { useMineLabel, useRegionPlace, useTagPlace } from './regionPlace'
import type { ResultSearchContext } from './ResultList'
import {
  loadPrefs,
  loadSettings,
  planCountToKeep,
  readVisit,
  resolveSettings,
  savePrefs,
  shownPlanCount,
  writeVisit,
  type PlanInputs,
  type SearchPrefs,
  saveSettings,
  writeSettings,
  type SearchSettings,
} from './searchSettings'
import { useSearchedWith } from './useSearchedWith'
import styles from './SearchPage.module.css'

/** The first screen's words (first screen redesign, user 2026-10-19). */
/** The first screen's head and example card [확정] (ADR-0139, handoff 5-A-1·2, main screen (나) 2026-10-20). */
export const START_TEXT = {
  // user 2026-10-20: "공공 전자도서관은 무료로 빌릴 수 있어요"
  eyebrow: '공공 전자도서관은 무료로 빌릴 수 있어요',
  // user 2026-10-20: "읽고 싶은 전자책, / 사기 전에 빌릴 수 있는 전자도서관부터"
  // without 빌릴 수 있는 (user 2026-10-20: "큰 글자 메인에서 빌릴 수 있는 이걸 빼달라니까")
  headline: ['읽고 싶은 전자책,', '사기 전에', '전자도서관부터'],
  // in the brand colour in the question above the search box, not in the headline (user 2026-10-20)
  headlineMark: '빌릴 수 있는',
  // names the example card's region and two of its pills, or the person's own region when picked (user 2026-10-20:
  // "우측 박스에 있는 것에 맞춰서 이 문구도 바꿔야지", "지금 위랑 아래랑 안 맞는데")
  lead: (home: string, others: readonly string[], basis: 'residence' | 'work' = 'residence') => {
    const where = basis === 'work' ? `${home}에서 일해도` : `${home}에 살아도`
    return [
      others.length > 0
        ? `${where} ${others.join('·')} 전자도서관에서 전자책을 빌릴 수 있어요.`
        : `${where} 다른 지역 전자도서관에서 전자책을 빌릴 수 있어요.`,
    ]
  },
  more: '자세히 →',
  // above the search box, the lead's second line moved here (user 2026-10-20: "어떤 책을 찾으세요? 이거 문구를 …
  // 이걸로 바꾸고 원래 거 지워")
  question: '전자책을 빌릴 수 있는 전자도서관에서 찾아 드릴게요.',
  // a region picked at random on every visit from START_EXAMPLES (user 2026-10-20: "한 100개 정도 해서 항상 랜덤값이
  // 뜨게", "예: 빼줘")
  exampleFoot: '전자도서관에서 빌릴 수 있어요',
  // before the person sets any 거주 or 직장 the card is only an example (user 2026-10-21: "만약에 서울 강서구에 살면")
  exampleIf: '만약에',
  exampleTitle: (place: string, basis: 'residence' | 'work' = 'residence') =>
    basis === 'work' ? `${place}에서 일하면` : `${place}에 살면`,
  // a card made from a workplace: the 거주 row asks for one
  livePill: '사는 곳',
  residence: '거주',
  work: '재직',
  // 「일하는 곳 +」 on every width (user 2026-10-20: "일하는 곳만 있는데 일하는 곳 + 이걸로", PC too: "모바일 처럼 바꿔")
  workPill: '일하는 곳',
  workRest: '+',
  open: '상관없이',
  rest: (count: number) => `+ ${count}곳`,
  restPhone: (count: number) => `+ ${count}`,
  only: (count: number) => `${count}곳`,
  none: '없어요',
} as const

/** Pills on a row: 4 for 거주 on wider screens, 3 on phones; 3 for 상관없이. */
const RESIDENCE_PILLS = 4
const OPEN_PILLS = 3
const PHONE_PILLS = 3

export interface ExampleCard {
  /** The region's id (START_EXAMPLES). */
  id: number
  place: string
  /** The region's own 시군구 ("강서구"). */
  home: string
  /** Up to two other places, among the first pills (the lead names them). */
  others: string[]
  /** 거주, or 재직 for someone who set only a workplace (user 2026-10-20); `residence` then holds the 재직 pills. */
  basis: 'residence' | 'work'
  residence: string[]
  residenceRest: number
  open: string[]
  openRest: number
}

/** `count` items of `items` in a random order (all when fewer). */
function sample<T>(items: readonly T[], count: number, random: () => number): T[] {
  const pool = [...items]
  const picked: T[] = []
  while (picked.length < count && pool.length > 0) picked.push(pool.splice(Math.floor(random() * pool.length), 1)[0])
  return picked
}

const homeOf = (place: string) => place.split(' ').slice(1).join(' ')

/**
 * Every region's example, grouped by the server's 시도 short name (the address guess names it the same way); all of
 * the country (user 2026-10-20: "랜덤 지역 예시데이터 … 전국 넣어줘", two places or more before).
 */
const EXAMPLES_BY_SIDO = [
  ...START_EXAMPLES.reduce((groups, one) => {
    const sido = one.place.split(' ')[0]
    groups.set(sido, [...(groups.get(sido) ?? []), one])
    return groups
  }, new Map<string, StartExampleData[]>()),
].map(([, group]) => group)

/**
 * One example at random: the region's own place first when it has one, the other pills at random. Wider screens show
 * four 거주 pills, phones the first three (own place and the two the lead names).
 */
export function pickExample(sido: string | null, random: () => number = Math.random): ExampleCard {
  // the visitor's 시도 from the address when it has examples, else 서울 (user 2026-10-20: "대충 모르겠으면 서울쪽으로")
  const group =
    EXAMPLES_BY_SIDO.find((one) => one[0].place.split(' ')[0] === sido) ??
    EXAMPLES_BY_SIDO.find((one) => one[0].place.split(' ')[0] === FALLBACK_SIDO) ??
    EXAMPLES_BY_SIDO[0]
  return cardOf(group[Math.floor(random() * group.length)], random)
}

/** The 시도 ask waits this long at most; the card then shows a 서울 example. */
const GUESS_TIMEOUT_MS = 1500

/** Random numbers read in turn (the same picks on every render). */
function seeded(seeds: readonly number[]): () => number {
  let at = 0
  return () => seeds[at++ % seeds.length]
}

/** When the 시도 is not known or has no examples. */
const FALLBACK_SIDO = '서울'

/**
 * The example of a region by its id, for the person who picked it; found by id since the screens name some 시도
 * otherwise (전남 for the server's 전남광주, user 2026-10-20: "광양시는 왜이래"). Null for an id with none.
 */
export function exampleOf(
  id: number,
  random: () => number = Math.random,
  basis: 'residence' | 'work' = 'residence',
): ExampleCard | null {
  // every region has one since 2026-10-20 (a region whose libraries name no place has no pills, only the count)
  const example = START_EXAMPLES.find((one) => one.id === id)
  return example ? cardOf(example, random, basis) : null
}

function cardOf(
  example: StartExampleData,
  random: () => number,
  basis: 'residence' | 'work' = 'residence',
): ExampleCard {
  // 세종 has no 시군구 of its own: "세종특별자치시", as the region labels show it
  const [sido, ...rest] = example.place.split(' ')
  const place = rest.join(' ').endsWith('특별자치시') ? rest.join(' ') : `${shownPrefix(sido, rest.join(' '))} ${rest.join(' ')}`
  const home = rest.join(' ')
  const places = basis === 'work' ? example.workPlaces : example.places
  const eligible = basis === 'work' ? example.workEligible : example.eligible
  const own = places[0] === home ? [home] : []
  const residence = [...own, ...sample(places.slice(own.length), RESIDENCE_PILLS - own.length, random)]
  const open = sample(example.openPlaces, OPEN_PILLS, random)
  return {
    id: example.id,
    // as the screens name it ("전남 광양시")
    place,
    home,
    others: residence.filter((place) => place !== home).slice(0, 2),
    basis,
    residence,
    residenceRest: eligible - residence.length,
    open,
    openRest: example.open - open.length,
  }
}

/** Lines joined by line breaks. */
function lines(parts: readonly string[], mark?: string) {
  return parts.map((part, index) => {
    const at = mark ? part.indexOf(mark) : -1
    return (
      <span key={part}>
        {index > 0 && <br />}
        {at < 0 || !mark ? (
          part
        ) : (
          <>
            {part.slice(0, at)}
            <span className={styles.headlineMark}>{mark}</span>
            {part.slice(at + mark.length)}
          </>
        )}
      </span>
    )
  })
}

/**
 * The example card where 「이렇게 이용해 보세요」 was (user 2026-10-20, ADR-0139 #2, handoff 5-A-2): "우리 동네만?"
 * answered by a picture, three rows 거주 · 재직 · 상관없이 of pills for an example region. Phones show three pills a row.
 * Someone who picked a region sees their own numbers here instead (5-A-4).
 */
function StartExample({ phone, card, isExample }: { phone: boolean; card: ExampleCard; isExample: boolean }) {
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
      ? {
          key: START_TEXT.residence,
          tone: 'mine',
          pills: [START_TEXT.livePill],
          rest: START_TEXT.workRest,
        }
      : { key: START_TEXT.residence, ...placed },
    // always 「일하는 곳 +」 on a 거주 card: a workplace brings more than its own 시군구's library (user 2026-10-20:
    // "광진구에 일하면 광진구 전자도서관만 가입 가능해? … 그냥 일하는곳 + 로")
    workCard
      ? { key: START_TEXT.work, ...placed }
      : {
          key: START_TEXT.work,
          tone: 'mine',
          pills: [START_TEXT.workPill],
          rest: START_TEXT.workRest,
        },
    { key: START_TEXT.open, tone: 'open', pills: card.open, rest: rest(card.openRest) },
  ]
  // always this card, never the numbers (user 2026-10-20: "어떤 경우에도 난 아래 박스 형태를 원하지 않아"): the person's
  // first 거주, else their first 직장 (a 재직 card), else the example
  return (
    <aside className={styles.steps} aria-labelledby={titleId}>
      {/* one line again (user 2026-10-20: "다음 전자도서관들에서 빌릴 수 있어요 이 문구도 없애줘") */}
      <h2 id={titleId} className={styles.stepsTitle}>
        {/* smaller and in the foot's grey (user 2026-10-21: "좀 작고 약간 회색 … 전자도서관에서 빌릴 수 있어요랑 같은 색") */}
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
      {/* bottom right, small and soft (user 2026-10-20: "우하단에 … 전자도서관에서 빌릴 수 있어요") */}
      <p className={styles.exampleFoot}>{START_TEXT.exampleFoot}</p>
    </aside>
  )
}

/**
 * S1 (flow.md cycle 04): `/` before a search, `/search?title=…&author=…` with results. The catalog search is the
 * default: 바로 보기 (books, then their libraries' live availability) or 책 먼저 고르기 (books only). With
 * "도서관에서 직접 찾기" on, the cycle 03 site search runs with the first filled field. Conditions, filter, scope,
 * visit and the two settings are in the address (ADR-0033); only the settings are also kept in this browser [확정].
 * Cycle 22 (D6): the search uses the regions and added libraries as they were when it was started (`searchedWith`);
 * changing them, or 방문 없이 가입, only saves them until the next [검색].
 */
export function SearchPage() {
  const [params, setParams] = useSearchParams()
  const navigate = useNavigate()
  // the first screen's example region, once a visit, in the visitor's 시도 by the address (user 2026-10-20); the lead
  // and the card both use it. Fixed random numbers, so a new render keeps the same pick.
  const [seeds] = useState(() => Array.from({ length: 16 }, () => Math.random()))
  const guess = useQuery({
    queryKey: ['sidoGuess'],
    queryFn: ({ signal }) => fetchSidoGuess(AbortSignal.any([signal, AbortSignal.timeout(GUESS_TIMEOUT_MS)])),
    staleTime: Infinity,
    retry: false,
  })
  const guessSettled = guess.isSuccess || guess.isError
  const card = useMemo(() => pickExample(guess.data?.sido ?? null, seeded(seeds)), [guess.data?.sido, seeds])
  const { regions: picked } = useRegions()
  const labels = useRegionLabels(useRegionTree().data)
  // the person's first 거주, else first 직장 (거주 wins, user 2026-10-20), for the card and the lead
  const pickedBasis: ExampleCard['basis'] | null =
    picked.residence.length > 0 ? 'residence' : picked.work.length > 0 ? 'work' : null
  const pickedId = pickedBasis === 'residence' ? picked.residence[0] : pickedBasis === 'work' ? picked.work[0] : null
  const pickedPlace = pickedId === null ? null : (labels.get(pickedId) ?? null)
  const ownCard = useMemo(
    () => (pickedId === null || pickedBasis === null ? null : exampleOf(pickedId, seeded(seeds), pickedBasis)),
    [pickedId, pickedBasis, seeds],
  )
  const lead = useMemo(() => {
    if (pickedBasis === null) return { home: card.home, others: card.others, basis: card.basis }
    if (ownCard !== null) return { home: ownCard.home, others: ownCard.others, basis: pickedBasis }
    if (pickedPlace === null) return null
    return { home: homeOf(pickedPlace), others: [], basis: pickedBasis }
  }, [pickedBasis, pickedPlace, card, ownCard])
  const [stored, setStored] = useState<SearchSettings>(() => loadSettings())
  const settings = resolveSettings(params, stored)
  // what is kept in this browser fills only what the address leaves out (user request 2026-10-05)
  const [prefs, setPrefs] = useState<SearchPrefs>(() => loadPrefs())
  const input = readBookSearch(params)
  const filter = readFilter(params, prefs.filter)
  // the results read 방문 없이 가입 from the address only; the switch keeps it for the next search (cycle 22 step 1-4)
  const visit = readVisit(params)
  const keepPrefs = (next: Partial<SearchPrefs>) => {
    savePrefs(next)
    setPrefs((current) => ({ ...current, ...next }))
  }

  const { regions, libraryIds, source, notices, accountFailed } = useRegions()
  const scope = readScope(params)
  const { searchedWith, retake, resolveAndRetake } = useSearchedWith()
  // the "찾을 도서관" box under the line (wider screens) and the chip's "찾을 도서관" sheet (phones), cycle 22 R1, R2
  const [box, setBox] = useState<BoxFocus | null>(null)
  const [sheetOpen, setSheetOpen] = useState(false)
  // direct search: where the "N곳" button goes (user requests 2026-10-05): phones at the right end of the
  // settings line, wider screens at the right end of the scope controls' row
  const [countSlot, setCountSlot] = useState<HTMLElement | null>(null)
  // phones: the scope controls fold into the settings panel (D안, user choice 2026-10-05)
  const phone = useMediaQuery('(width <= 640px)')

  // direct search: the first filled field only (spec 3.7)
  const condition = readCondition(params)
  const directQuery = condition ? normalizeQuery(condition.text) : ''
  const directValid = settings.direct && condition !== null && checkQuery(directQuery) === null

  // catalog search: every filled field, normalized; nothing is sent while one of them is invalid
  const residenceKey = searchedWith?.residence.join(',') ?? ''
  const workKey = searchedWith?.work.join(',') ?? ''
  const libraryKey = searchedWith?.libraryIds.join(',') ?? ''
  const hasSnapshot = searchedWith !== null
  const bookQuery = useMemo<BookQuery | null>(() => {
    if (!input || settings.direct || !hasSnapshot) return null
    const normalized = {
      title: normalizeQuery(input.title),
      author: normalizeQuery(input.author),
      publisher: normalizeQuery(input.publisher),
    }
    const fields = filledFields({ ...input, ...normalized })
    if (fields.some((field) => checkQuery(normalized[field]) !== null)) return null
    return {
      title: normalized.title || null,
      author: normalized.author || null,
      publisher: normalized.publisher || null,
      exact: input.exact,
      roles: input.roles,
      sort: input.sort,
      scope,
      visit,
      residenceRegionIds: residenceKey ? residenceKey.split(',').map(Number) : [],
      workRegionIds: workKey ? workKey.split(',').map(Number) : [],
      // the account's after login (AC-6), this browser's before (cycle 07, AC-18), as they were at the search
      libraryIds: libraryKey ? libraryKey.split(',').map(Number) : [],
      page: 1,
    }
    // the key string stands for the input object, which is new on every render
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [params.toString(), settings.direct, hasSnapshot, scope, visit, residenceKey, workKey, libraryKey])

  const hasSearch = settings.direct ? directValid : bookQuery !== null
  /**
   * Opens the region choice: on the main page the box on wider screens, the "찾을 도서관" sheet on phones, whatever the
   * my page set (it changes the first 거주 only); elsewhere the my page (user 2026-10-20).
   */
  const openPicker = () => {
    if (!start) navigate('/my-libraries')
    else if (phone) setSheetOpen(true)
    else setBox('residence')
  }
  // 「전자책 검색」 (header menu 도서검색, user 2026-10-16, design A): `/search` before a search keeps the results'
  // layout, with the screen's name at the top left and a short note where the results will come
  const { pathname } = useLocation()
  const ebookStart = !hasSearch && pathname === '/search'
  // `/` before a search: the first screen with its headline and steps (user 2026-10-19)
  const start = !hasSearch && !ebookStart

  // The form starts again only when the search words in the address change (a search, back or forward). A switch,
  // the filter, the sort or the settings leave what is being typed and the narrowing being typed as they are (user report
  // 2026-10-14: the words typed went away on "방문 없이 가입").
  const formKey = input
    ? JSON.stringify([input.title, input.author, input.publisher, input.exact, input.roles])
    : ''

  /**
   * Keeps filter, the sort and the settings; replaces the conditions. A new search is always `joinable` (D10) and
   * takes the stored 방문 없이 가입 and the current regions (the snapshot is taken again).
   */
  const search = (next: BookSearchInput) => {
    const address = new URLSearchParams()
    writeFilter(filter, address)
    writeVisit(prefs.visit, address)
    retake()
    // the sort is changed outside the form (the result list), so the address has the current one
    writeBookSearch({ ...next, sort: input?.sort ?? next.sort }, address)
    writeSettings(settings, address)
    navigate(`/search?${address}`)
  }
  const replaceParam = (name: string, value: string | null) => {
    const updated = new URLSearchParams(params)
    if (value === null) updated.delete(name)
    else updated.set(name, value)
    setParams(updated, { replace: true })
  }
  const changeSettings = (next: SearchSettings) => {
    // with direct search on, 책 먼저 고르기 cannot be used [안]
    const effective = next.direct && next.view === 'books' ? { ...next, view: 'instant' as const } : next
    saveSettings(effective)
    setStored(effective)
    setParams(writeSettings(effective, new URLSearchParams(params)), { replace: true })
  }
  const changeSort = (sort: BookSort) => {
    replaceParam('sort', sort === 'default' ? null : sort)
  }
  const changeFilter = (next: ResultFilter) => {
    keepPrefs({ filter: next })
    setParams(writeFilter(next, new URLSearchParams(params)), { replace: true })
  }
  // the empty result's button: only the address changes, the stored setting stays [안]
  const goDirect = () => replaceParam('direct', 'on')
  const onInvalidRegion = resolveAndRetake

  // The four buttons inside the results search again at once (cycle 22 R8-3, D10): the address changes and the
  // snapshot is taken again, as with [검색].
  const searchAgain = (change: (address: URLSearchParams) => void) => {
    const address = new URLSearchParams(params)
    change(address)
    retake()
    navigate(`/search?${address}`)
  }
  const searchedResidence = searchedWith?.residence ?? []
  const searchedWork = searchedWith?.work ?? []
  /**
   * The plan size of the search on screen, when it is known: only the direct search's first stream carries the whole
   * plan (`targets.libraries`); 바로 보기's holding stream names only the libraries that hold the books found, and
   * 책 먼저 고르기 has none.
   */
  const directKey = JSON.stringify([condition?.field, directQuery, scope, visit, residenceKey, workKey, libraryKey])
  const [plan, setPlan] = useState<{ key: string; count: number } | null>(null)
  const knownPlan = settings.direct && plan?.key === directKey ? plan.count : null
  const searchedInputs: PlanInputs = {
    source,
    residence: searchedResidence,
    work: searchedWork,
    libraryIds: searchedWith?.libraryIds ?? [],
    visit,
  }
  const searchedMine =
    searchedResidence.length > 0 || searchedWork.length > 0 || (searchedWith?.libraryIds.length ?? 0) > 0
  const mineLabel = useMineLabel(searchedResidence, searchedWork)
  // the places saved now, for the grey line that asks for [검색] after a region was picked (R7-2)
  const nowResidencePlace = useRegionPlace(regions.residence)
  const nowWorkPlace = useRegionPlace(regions.work)
  /**
   * A whole plan is known (D5): kept as `planCount` for the folded line (not for `scope=all`), and, for a search
   * without regions or added libraries and with the visit-only libraries in, as `openCount` (R3-4).
   */
  const keepPlan = (count: number) => {
    setPlan({ key: directKey, count })
    const next: Partial<SearchPrefs> = {}
    const planCount = planCountToKeep(scope, searchedInputs, count)
    if (planCount) next.planCount = planCount
    if (scope === 'joinable' && !searchedMine && visit === 'any') next.openCount = count
    if (Object.keys(next).length > 0) keepPrefs(next)
  }
  // the place words of the [{강서구} 거주 가입] tags: the searched residences, else workplaces, both when both
  const tagPlace = useTagPlace(
    searchedResidence.length > 0 && searchedWork.length > 0
      ? [...searchedResidence, ...searchedWork]
      : searchedResidence.length > 0
        ? searchedResidence
        : searchedWork,
  )
  // 「온라인 가입만」 on or off: kept for the next search, and this search again with it (user 2026-10-20)
  const switchVisit = (next: VisitFilter) => {
    keepPrefs({ visit: next })
    searchAgain((address) => writeVisit(next, address))
  }
  const searchContext: ResultSearchContext = {
    hasMine: searchedMine,
    tagPlace,
    mineLabel,
    regionBasis:
      searchedResidence.length > 0 && searchedWork.length > 0
        ? 'both'
        : searchedWork.length > 0
          ? 'work'
          : searchedResidence.length > 0
            ? 'residence'
            : 'both',
    scope,
    visit,
    onSearchAll: () => searchAgain((address) => address.set('scope', 'all')),
    onVisitNone: () => switchVisit('none'),
    onVisitBack: () => switchVisit('any'),
  }
  // the grouped results on screen (direct search, or 바로 보기), the ones that take `searchContext`
  const groupedResults = (settings.direct && directValid && condition && searchedWith) || (bookQuery && settings.view !== 'books')
  /**
   * The condition card of a results screen of a search made without regions or added libraries, not 전국 and not after
   * a failed account read (the results' own line R7 before, user 2026-10-19: "이거 중복이라서 … 위에 좀 써줘").
   */
  const scopeSearched: ScopeSearched | null =
    groupedResults && !searchedMine && scope !== 'all' && !accountFailed
      ? {
          // R7-1 {N}: the whole plan when this search knows it, else the kept count for the same inputs, else the
          // number of 어디 살든 가입 libraries (a search without regions plans those, unless the visit-only ones are
          // left out)
          planCount:
            knownPlan ??
            shownPlanCount(prefs, searchedInputs) ??
            (scope === 'joinable' && visit === 'any' ? prefs.openCount : null),
          // R7-2: checked since; the grey line under the card then asks for [검색]
          regionsPicked: hasRegions(regions) || libraryIds.length > 0,
          picked:
            regions.residence.length > 0 && nowResidencePlace
              ? { field: 'residence', place: nowResidencePlace }
              : regions.work.length > 0 && nowWorkPlace
                ? { field: 'work', place: nowWorkPlace }
                : libraryIds.length > 0
                  ? { field: 'libraries' }
                  : null,
        }
      : null

  const bookLinkSearch = new URLSearchParams()
  writeFilter(filter, bookLinkSearch)
  if (scope === 'all') bookLinkSearch.set('scope', 'all')
  writeVisit(visit, bookLinkSearch)

  // only kept for the next search (cycle 22 step 1-4, R1-3); the results go on reading the address
  const changeVisit = (next: VisitFilter) => keepPrefs({ visit: next })
  const keepOpenCount = (count: number) => keepPrefs({ openCount: count })
  const scopeCommon = {
    visit: prefs.visit,
    onVisitChange: changeVisit,
    planCount: prefs.planCount,
    openCount: prefs.openCount,
    onOpenCount: keepOpenCount,
    searched: scopeSearched,
    // the switch now differs from the search on screen (user 2026-10-19): ask for [검색]
    visitChanged: hasSearch && prefs.visit !== visit,
    // the regions or added libraries differ from the ones the search on screen was made with (user 2026-10-20)
    regionsChanged:
      hasSearch &&
      searchedWith !== null &&
      !accountFailed &&
      (searchedWith.residence.join(',') !== regions.residence.join(',') ||
        searchedWith.work.join(',') !== regions.work.join(',') ||
        searchedWith.libraryIds.join(',') !== libraryIds.join(',')),
    // [검색] in the grey line: the search on screen again with what is saved now
    onSearchAgain: () => searchAgain((address) => writeVisit(prefs.visit, address)),
  }


  return (
    <div
      className={styles.page}
      data-state={hasSearch ? 'results' : ebookStart ? 'ebook' : 'start'}
      // phones: the app layout fits this screen into the window (no scroll, user request 2026-10-05); not the first
      // screen since its example card sits below the fold (handoff 5-A-2)
      data-fits-screen={hasSearch || start ? undefined : true}
    >
      <section className={styles.searchArea}>
        {ebookStart && <h1 className={styles.screenTitle}>전자책 검색</h1>}
        {start && (
          <>
            <div className={styles.intro}>
              <p className={styles.eyebrow}>{START_TEXT.eyebrow}</p>
              <h1 className={styles.headline}>{lines(START_TEXT.headline)}</h1>
              <p className={styles.lead}>
                {lead && guessSettled && lines(START_TEXT.lead(lead.home, lead.others, lead.basis))}
                {/* no 「자세히 →」 (user 2026-10-20: "자세히 → 이거 빼줘 pc"); 소개 is in the header */}
              </p>
            </div>
            {!phone && (
              <div className={styles.stepsSlot}>
                {guessSettled && <StartExample phone={false} card={ownCard ?? card} isExample={ownCard === null} />}
              </div>
            )}
            {/* 빌릴 수 있는 in purple here too (user 2026-10-20) */}
            <p className={styles.question}>{lines([START_TEXT.question], START_TEXT.headlineMark)}</p>
            {/* the region button between that line and the search box (user 2026-10-20: "지역을 선택해 주세요 버튼이랑 검색
                창 위치 바꿔줘", "찾아 드릴게요가 버튼보다 위에") */}
            <div className={styles.startPick}>
              {phone ? (
                <LibraryScopeChip {...scopeCommon} onOpen={openPicker} />
              ) : (
                <LibraryScopeLine {...scopeCommon} box={box} onBoxChange={setBox} />
              )}
            </div>
          </>
        )}
        <SearchForm
          // no 「온라인 가입만」 chip by the search box any more (user 2026-10-21: "온라인 가입만 이거 버튼 빼주라"); the
          // setting stays on the my page
          key={formKey}
          initial={input ?? EMPTY_INPUT}
          settings={settings}
          hasResults={hasSearch}
          centered={start}
          onSearch={search}
          onSettingsChange={changeSettings}
          settingsSheet={phone}
          chip={
            !phone || start ? undefined : (
              <ScopeNote
                regionsChanged={scopeCommon.regionsChanged}
                visitChanged={scopeCommon.visitChanged}
                onSearchAgain={scopeCommon.onSearchAgain}
              />
            )
          }
          // one line under the search box: what the search uses, and the box to change it (cycle 22 R1, ADR-0138);
          // above the narrowing chips as on phones (user 2026-10-20: "폰 처럼 지역설정이 상세조건보다 위로")
          beforeChips={
            phone || start ? undefined : (
              <div className={styles.scopeRow}>
                <ScopeNote
                  regionsChanged={scopeCommon.regionsChanged}
                  visitChanged={scopeCommon.visitChanged}
                  onSearchAgain={scopeCommon.onSearchAgain}
                />
                {settings.direct && directValid && <div ref={setCountSlot} className={styles.countSlot} />}
              </div>
            )
          }
          lineEnd={
            // 바로 보기's sort is on the result tabs' line on phones too (user 2026-10-20)
            phone && settings.direct && directValid ? (
              <div className={styles.directLineEnd}>
                <NoSort />
                <div ref={setCountSlot} className={styles.countSlot} />
              </div>
            ) : undefined
          }
        />
        {/* phones: the chip's own sheet, only "찾을 도서관" (user 2026-10-19) */}
        {phone && start && <LibraryScopeSheet open={sheetOpen} onOpenChange={setSheetOpen} />}
        {/* phones: head → search box → region line → this card, below the fold (handoff 5-A-2) */}
        {start && phone && guessSettled && <StartExample phone card={ownCard ?? card} isExample={ownCard === null} />}
      </section>

      {notices.length > 0 && (
        <div className={styles.notices}>
          {notices.map((notice) => (
            <p key={notice.message} className={styles.notice} data-kind={notice.kind} role="status">
              {notice.message}
              {notice.kind === 'split' && (
                <>
                  {' · '}
                  <button type="button" className={styles.noticeAction} onClick={() => openPicker()}>
                    설정
                  </button>
                </>
              )}
            </p>
          ))}
        </div>
      )}

      {ebookStart && (
        <section className={styles.results}>
          <p className={styles.ebookNote}>책 제목을 넣으면, 그 책을 지금 빌릴 수 있는 전자도서관을 여기에 보여 드려요</p>
        </section>
      )}

      {settings.direct && directValid && condition && searchedWith && (
        <DirectResults
          key={`${condition.field}:${directQuery}`}
          field={condition.field}
          query={directQuery}
          filter={filter}
          scope={scope}
          visit={visit}
          searchedWith={searchedWith}
          onInvalidRegion={onInvalidRegion}
          onFilterChange={changeFilter}
          onEditRegions={() => openPicker()}
          onPlan={keepPlan}
          countSlot={countSlot}
          searchContext={searchContext}
        />
      )}
      {bookQuery && (
        <section className={styles.results}>
          {settings.view === 'books' ? (
            <BookList
              key={JSON.stringify(bookQuery)}
              query={bookQuery}
              bookLinkSearch={bookLinkSearch.toString()}
              onSortChange={changeSort}
              onDirect={goDirect}
              onInvalidRegion={onInvalidRegion}
            />
          ) : (
            <InstantResults
              key={`${JSON.stringify(bookQuery)}:${filter}`}
              query={bookQuery}
              filter={filter}
              onFilterChange={changeFilter}
              onSortChange={changeSort}
              onDirect={goDirect}
              onInvalidRegion={onInvalidRegion}
              searchContext={searchContext}
            />
          )}
        </section>
      )}

    </div>
  )
}

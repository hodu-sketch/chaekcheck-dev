import { useMemo, useState } from 'react'
import { useLocation, useNavigate, useSearchParams } from 'react-router'
import type { BookQuery } from '../../api/client'
import type { BookSort, ResultFilter, VisitFilter } from '../../api/types'
import { readFilter, writeFilter } from './resultFilter'
import { checkQuery, normalizeQuery } from '../../lib/query'
import { useMediaQuery } from '../../lib/useMediaQuery'
import { useRegions } from '../region/RegionContext'
import { hasRegions } from '../region/regionStorage'
import { BookList } from './BookList'
import { DirectResults, NoSort } from './DirectResults'
import { InstantResults } from './InstantResults'
import { LibraryScopeBody, LibraryScopeChip, LibraryScopeLine, VisitSwitch, type BoxFocus } from './LibraryScopeLine'
import { EMPTY_INPUT, filledFields, readBookSearch, readCondition, writeBookSearch, type BookSearchInput } from './searchCondition'
import { SearchForm } from './SearchForm'
import { readScope } from './readScope'
import { useRegionPlace } from './regionPlace'
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
import type { SettingsSection } from './SettingsPanel'
import { SortSelect } from './SortSelect'
import { useSearchedWith } from './useSearchedWith'
import styles from './SearchPage.module.css'

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
  // the "찾을 도서관" box under the line (wider screens) and the settings sheet on its section (phones), cycle 22 R1, R2
  const [box, setBox] = useState<BoxFocus | null>(null)
  const [sheetOpen, setSheetOpen] = useState(false)
  const [sheetSection, setSheetSection] = useState<SettingsSection>('')
  const [sheetFocus, setSheetFocus] = useState<BoxFocus>('residence')
  // direct search: where the "N곳" button goes (user requests 2026-10-05): phones at the right end of the
  // "상세검색 · 설정" line, wider screens at the right end of the scope controls' row
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
  /** Opens the region choice: the box on wider screens, the settings sheet on "찾을 도서관" on phones (R1-4, R2-1, R7-1). */
  const openPicker = (focus: BoxFocus = 'residence') => {
    if (phone) {
      setSheetFocus(focus)
      setSheetSection('scope')
      setSheetOpen(true)
    } else {
      setBox(focus)
    }
  }
  // 「전자책 검색」 (header menu 도서검색, user 2026-10-16, design A): `/search` before a search keeps the results'
  // layout, with the screen's name at the top left and a short note where the results will come
  const { pathname } = useLocation()
  const ebookStart = !hasSearch && pathname === '/search'

  // The form starts again only when the search words in the address change (a search, back or forward). A switch,
  // the filter, the sort or the settings leave what is being typed and the open 상세검색 as they are (user report
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
  const residencePlace = useRegionPlace(searchedResidence)
  const workPlace = useRegionPlace(searchedWork)
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
  const searchContext: ResultSearchContext = {
    hasMine: searchedMine,
    mineLabel: searchedResidence.length > 0 ? residencePlace : workPlace,
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
    accountFailed,
    // R7-1 {N}: the whole plan when this search knows it, else the kept count for the same inputs, else the number of
    // 어디 살든 가입 libraries (a search without regions plans those, unless the visit-only ones are left out)
    planCount:
      knownPlan ??
      shownPlanCount(prefs, searchedInputs) ??
      (scope === 'joinable' && visit === 'any' ? prefs.openCount : null),
    // R7-2: checked since a search made without any; the green line then asks for [검색]
    regionsPicked: !searchedMine && (hasRegions(regions) || libraryIds.length > 0),
    onSearchAll: () => searchAgain((address) => address.set('scope', 'all')),
    onVisitNone: () => {
      keepPrefs({ visit: 'none' })
      searchAgain((address) => writeVisit('none', address))
    },
    onVisitBack: () => {
      keepPrefs({ visit: 'any' })
      searchAgain((address) => writeVisit('any', address))
    },
    onPickRegions: () => openPicker('residence'),
  }

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
    scope,
    allCount: scope === 'all' ? knownPlan : null,
    // [내 도서관만] (R4-2): the same search without scope=all
    onSearchMine: () => searchAgain((address) => address.delete('scope')),
  }
  // the dot on the settings button: not the default (joinable, visit-only libraries kept)
  const scopeChanged = scope === 'all' || prefs.visit === 'none'

  return (
    <div
      className={styles.page}
      data-state={hasSearch ? 'results' : ebookStart ? 'ebook' : 'start'}
      // phones: the app layout fits this screen into the window (no scroll, user request 2026-10-05)
      data-fits-screen={hasSearch ? undefined : true}
    >
      <section className={styles.searchArea}>
        {ebookStart && <h1 className={styles.screenTitle}>전자책 검색</h1>}
        {!hasSearch && !ebookStart && (
          <h1 className={styles.headline}>
            <em>지금 빌릴 수 있는</em>
            <br />
            전자도서관을 찾아 드려요
          </h1>
        )}
        <SearchForm
          key={formKey}
          initial={input ?? EMPTY_INPUT}
          settings={settings}
          hasResults={hasSearch}
          centered={!hasSearch && !ebookStart}
          onSearch={search}
          onSettingsChange={changeSettings}
          settingsScope={
            phone ? (
              <LibraryScopeBody
                key={sheetFocus}
                focus={sheetFocus}
                visit={prefs.visit}
                onVisitChange={changeVisit}
                openCount={prefs.openCount}
                onOpenCount={keepOpenCount}
                onLeave={() => setSheetOpen(false)}
                sheet
              />
            ) : undefined
          }
          settingsMarked={phone && scopeChanged}
          settingsVisit={phone ? undefined : <VisitSwitch visit={prefs.visit} onChange={changeVisit} />}
          settingsControl={{
            open: sheetOpen,
            onOpenChange: (open) => {
              setSheetOpen(open)
              if (!open) setSheetFocus('residence')
            },
            section: sheetSection,
            onSectionChange: setSheetSection,
          }}
          chip={phone ? <LibraryScopeChip {...scopeCommon} onOpen={() => openPicker('residence')} /> : undefined}
          lineEnd={
            phone && bookQuery ? (
              <SortSelect value={bookQuery.sort} onChange={changeSort} />
            ) : phone && settings.direct && directValid ? (
              <div className={styles.directLineEnd}>
                <NoSort />
                <div ref={setCountSlot} className={styles.countSlot} />
              </div>
            ) : undefined
          }
        />
        {/* one line under the search box: what the search uses, and the box to change it (cycle 22 R1, ADR-0138) */}
        {!phone && (
          <div className={styles.scopeRow}>
            <LibraryScopeLine {...scopeCommon} box={box} onBoxChange={setBox} centered={!hasSearch && !ebookStart} />
            {settings.direct && directValid && <div ref={setCountSlot} className={styles.countSlot} />}
          </div>
        )}
      </section>

      {notices.length > 0 && (
        <div className={styles.notices}>
          {notices.map((notice) => (
            <p key={notice.message} className={styles.notice} data-kind={notice.kind} role="status">
              {notice.message}
              {notice.kind === 'split' && (
                <>
                  {' · '}
                  <button type="button" className={styles.noticeAction} onClick={() => openPicker('residence')}>
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
          onEditRegions={() => openPicker('residence')}
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

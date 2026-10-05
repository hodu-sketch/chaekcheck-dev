import { useCallback, useMemo, useState } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router'
import type { BookQuery } from '../../api/client'
import type { BookSort, ResultFilter, VisitFilter } from '../../api/types'
import { readFilter, writeFilter } from './resultFilter'
import { checkQuery, normalizeQuery } from '../../lib/query'
import { useMediaQuery } from '../../lib/useMediaQuery'
import { useRegions } from '../region/RegionContext'
import { RegionModal } from '../region/RegionModal'
import { hasRegions } from '../region/regionStorage'
import { RegionSummary } from '../region/RegionSummary'
import { useOptionalMyLibrary } from '../mylibrary/MyLibraryContext'
import { MyLibrarySummary } from '../mylibrary/MyLibrarySummary'
import { BookList } from './BookList'
import { DirectResults } from './DirectResults'
import { InstantResults } from './InstantResults'
import { LibraryScopeToggle } from './LibraryScopeToggle'
import { EMPTY_INPUT, filledFields, readBookSearch, readCondition, writeBookSearch, type BookSearchInput } from './searchCondition'
import { SearchForm } from './SearchForm'
import { joinableLabel, useLibraryScope } from './useLibraryScope'
import {
  loadSettings,
  resolveSettings,
  saveSettings,
  writeSettings,
  type SearchSettings,
} from './searchSettings'
import { SortSelect } from './SortSelect'
import { VisitToggle } from './VisitToggle'
import scopeStyles from './ScopeSummary.module.css'
import styles from './SearchPage.module.css'

/**
 * S1 (flow.md cycle 04): `/` before a search, `/search?title=…&author=…` with results. The catalog search is the
 * default: 바로 보기 (books, then their libraries' live availability) or 책 먼저 고르기 (books only). With
 * "도서관에서 직접 찾기" on, the cycle 03 site search runs with the first filled field. Conditions, filter, scope,
 * visit and the two settings are in the address (ADR-0033); only the settings are also kept in this browser [확정].
 */
export function SearchPage() {
  const [params, setParams] = useSearchParams()
  const navigate = useNavigate()
  const [stored, setStored] = useState<SearchSettings>(() => loadSettings())
  const settings = resolveSettings(params, stored)
  const input = readBookSearch(params)
  const filter = readFilter(params)
  const visit: VisitFilter = params.get('visit') === 'none' ? 'none' : 'any'

  const { regions, libraryIds, source, ready, notices, setRegions, resolveStoredRegions } = useRegions()
  const { scope, mine, change: changeScope, addressScope } = useLibraryScope(params, setParams)
  // "내 도서관 관리" before login too (cycle 07 flow S1); only screens without the provider (tests) keep "내 지역"
  const myLibrary = useOptionalMyLibrary()
  const [modalOpen, setModalOpen] = useState(false)
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
  const residenceKey = regions.residence.join(',')
  const workKey = regions.work.join(',')
  const libraryKey = libraryIds.join(',')
  const bookQuery = useMemo<BookQuery | null>(() => {
    if (!input || settings.direct || !ready) return null
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
      // sent with both switch values: the account's after login (AC-6), this browser's before (cycle 07, AC-18)
      libraryIds: libraryKey ? libraryKey.split(',').map(Number) : [],
      page: 1,
    }
    // the key string stands for the input object, which is new on every render
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [params.toString(), settings.direct, ready, scope, visit, residenceKey, workKey, libraryKey])

  const hasSearch = settings.direct ? directValid : bookQuery !== null

  /** Keeps filter, scope, visit and the settings; replaces the conditions. */
  const search = (next: BookSearchInput) => {
    const address = new URLSearchParams()
    writeFilter(filter, address)
    if (addressScope) address.set('scope', addressScope)
    if (visit === 'none') address.set('visit', 'none')
    writeBookSearch(next, address)
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
  const changeSort = (sort: BookSort) => replaceParam('sort', sort === 'default' ? null : sort)
  const changeFilter = (next: ResultFilter) => setParams(writeFilter(next, new URLSearchParams(params)), { replace: true })
  // the empty result's button: only the address changes, the stored setting stays [안]
  const goDirect = () => replaceParam('direct', 'on')
  const onInvalidRegion = useCallback(() => resolveStoredRegions(), [resolveStoredRegions])

  const bookLinkSearch = new URLSearchParams()
  writeFilter(filter, bookLinkSearch)
  if (addressScope) bookLinkSearch.set('scope', addressScope)
  if (visit === 'none') bookLinkSearch.set('visit', 'none')

  const changeVisit = (next: VisitFilter) => replaceParam('visit', next === 'none' ? 'none' : null)
  const scopeSwitch = (
    <LibraryScopeToggle scope={scope} hasRegions={hasRegions(regions)} mine={mine} onChange={changeScope} />
  )
  const visitToggle = <VisitToggle visit={visit} onChange={changeVisit} />
  // in the settings panel a plain way on: S10 after login, S2 before (the summary pop-up is for wider screens)
  const phoneMine = myLibrary ? (
    <Link to="/my-libraries" className={scopeStyles.trigger}>
      내 도서관 관리
    </Link>
  ) : (
    <button type="button" className={scopeStyles.trigger} onClick={() => setModalOpen(true)}>
      {hasRegions(regions) ? '내 지역 변경' : '내 지역 설정'}
    </button>
  )
  // the dot on the settings button: not the default scope (내 도서관 when there is one after login, else 전체)
  const defaultScope = mine && (hasRegions(regions) || libraryIds.length > 0) ? 'joinable' : 'all'
  const scopeChanged = scope !== defaultScope || visit === 'none'
  const scopeValue = [scope === 'all' ? '전체 도서관' : joinableLabel(hasRegions(regions), mine), visit === 'none' && '방문 없이']
    .filter(Boolean)
    .join(' · ')

  return (
    <div
      className={styles.page}
      data-state={hasSearch ? 'results' : 'start'}
      // phones: the app layout fits this screen into the window (no scroll, user request 2026-10-05)
      data-fits-screen={hasSearch ? undefined : true}
    >
      <section className={styles.searchArea}>
        {!hasSearch && (
          <h1 className={styles.headline}>
            <em>지금 빌릴 수 있는</em>
            <br />
            전자도서관을 찾아 드려요
          </h1>
        )}
        <SearchForm
          key={params.toString()}
          initial={input ?? EMPTY_INPUT}
          settings={settings}
          hasResults={hasSearch}
          onSearch={search}
          onSettingsChange={changeSettings}
          settingsScope={
            phone ? (
              <>
                {scopeSwitch}
                {visitToggle}
                {phoneMine}
              </>
            ) : undefined
          }
          settingsScopeValue={phone ? scopeValue : undefined}
          settingsMarked={phone && scopeChanged}
          lineEnd={
            phone && bookQuery ? (
              <SortSelect value={bookQuery.sort} onChange={changeSort} />
            ) : phone && settings.direct && directValid ? (
              <div ref={setCountSlot} className={styles.countSlot} />
            ) : undefined
          }
        />
        {/* one line: what to search, then what "내 도서관"/"내 지역" holds behind a button (user request 2026-10-05) */}
        {!phone && (
          <div className={styles.scopeRow}>
            <div className={styles.toggles}>
              {scopeSwitch}
              {visitToggle}
              {myLibrary ? <MyLibrarySummary /> : <RegionSummary regions={regions} onEdit={() => setModalOpen(true)} />}
            </div>
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
                  <button type="button" className={styles.noticeAction} onClick={() => setModalOpen(true)}>
                    설정
                  </button>
                </>
              )}
            </p>
          ))}
        </div>
      )}

      {settings.direct && directValid && condition && (
        <DirectResults
          key={`${condition.field}:${directQuery}`}
          field={condition.field}
          query={directQuery}
          filter={filter}
          scope={scope}
          visit={visit}
          onFilterChange={changeFilter}
          onEditRegions={() => setModalOpen(true)}
          countSlot={countSlot}
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
            />
          )}
        </section>
      )}

      <RegionModal
        open={modalOpen}
        onOpenChange={setModalOpen}
        regions={regions}
        savedTo={source === 'account' ? 'account' : 'browser'}
        onSave={async (next) => {
          // same conditions search again with the new regions (flow.md S2); a failed save keeps the modal open
          await setRegions(next)
          setModalOpen(false)
        }}
      />
    </div>
  )
}

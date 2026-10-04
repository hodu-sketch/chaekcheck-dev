import { useCallback, useMemo, useState } from 'react'
import { useNavigate, useSearchParams } from 'react-router'
import type { BookQuery } from '../../api/client'
import type { BookSort, LibraryScope, ResultFilter, VisitFilter } from '../../api/types'
import { checkQuery, normalizeQuery } from '../../lib/query'
import { useRegions } from '../region/RegionContext'
import { RegionModal } from '../region/RegionModal'
import { hasRegions } from '../region/regionStorage'
import { RegionSummary } from '../region/RegionSummary'
import { BookList } from './BookList'
import { DirectResults } from './DirectResults'
import { InstantResults } from './InstantResults'
import { LibraryScopeToggle } from './LibraryScopeToggle'
import { EMPTY_INPUT, filledFields, readBookSearch, readCondition, writeBookSearch, type BookSearchInput } from './searchCondition'
import { SearchForm } from './SearchForm'
import { loadSettings, resolveSettings, saveSettings, writeSettings, type SearchSettings } from './searchSettings'
import { VisitToggle } from './VisitToggle'
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
  const filter: ResultFilter = params.get('filter') === 'available' ? 'available' : 'all'
  const scope: LibraryScope = params.get('scope') === 'joinable' ? 'joinable' : 'all'
  const visit: VisitFilter = params.get('visit') === 'none' ? 'none' : 'any'

  const { regions, source, ready, notices, setRegions, resolveStoredRegions } = useRegions()
  const [modalOpen, setModalOpen] = useState(false)

  // direct search: the first filled field only (spec 3.7)
  const condition = readCondition(params)
  const directQuery = condition ? normalizeQuery(condition.text) : ''
  const directValid = settings.direct && condition !== null && checkQuery(directQuery) === null

  // catalog search: every filled field, normalized; nothing is sent while one of them is invalid
  const residenceKey = regions.residence.join(',')
  const workKey = regions.work.join(',')
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
      // libraries added by hand: wired in cycle 06 step 10
      libraryIds: [] as number[],
      page: 1,
    }
    // the key string stands for the input object, which is new on every render
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [params.toString(), settings.direct, ready, scope, visit, residenceKey, workKey])

  const hasSearch = settings.direct ? directValid : bookQuery !== null

  /** Keeps filter, scope, visit and the settings; replaces the conditions. */
  const search = (next: BookSearchInput) => {
    const address = new URLSearchParams()
    if (filter === 'available') address.set('filter', 'available')
    if (scope === 'joinable') address.set('scope', 'joinable')
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
  const changeFilter = (next: ResultFilter) => replaceParam('filter', next === 'available' ? 'available' : null)
  // the empty result's button: only the address changes, the stored setting stays [안]
  const goDirect = () => replaceParam('direct', 'on')
  const onInvalidRegion = useCallback(() => resolveStoredRegions(), [resolveStoredRegions])

  const bookLinkSearch = new URLSearchParams()
  if (filter === 'available') bookLinkSearch.set('filter', 'available')
  if (scope === 'joinable') bookLinkSearch.set('scope', 'joinable')
  if (visit === 'none') bookLinkSearch.set('visit', 'none')

  return (
    <div className={styles.page} data-state={hasSearch ? 'results' : 'start'}>
      <section className={styles.searchArea}>
        {!hasSearch && (
          <h1 className={styles.headline}>
            읽고 싶은 책,
            <br />
            <em>지금 빌릴 수 있는</em> 전자도서관을 찾아 드려요
          </h1>
        )}
        <SearchForm
          key={params.toString()}
          initial={input ?? EMPTY_INPUT}
          settings={settings}
          hasResults={hasSearch}
          onSearch={search}
          onSettingsChange={changeSettings}
        />
        <RegionSummary regions={regions} onEdit={() => setModalOpen(true)} />
        <div className={styles.toggles}>
          <LibraryScopeToggle
            scope={scope}
            hasRegions={hasRegions(regions)}
            onChange={(next) => replaceParam('scope', next === 'joinable' ? 'joinable' : null)}
          />
          <VisitToggle visit={visit} onChange={(next) => replaceParam('visit', next === 'none' ? 'none' : null)} />
        </div>
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

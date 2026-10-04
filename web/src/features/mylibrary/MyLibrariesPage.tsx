import { useQuery } from '@tanstack/react-query'
import { useId, useMemo, useState, type ReactNode } from 'react'
import { Navigate } from 'react-router'
import { ApiError, fetchLibraries } from '../../api/client'
import { addMyLibrary, removeMyLibrary } from '../../api/myLibraries'
import type { LibraryEligibilityRow } from '../../api/types'
import { useAuth } from '../auth/AuthContext'
import { useRegions } from '../region/RegionContext'
import { RegionModal } from '../region/RegionModal'
import { useRegionLabels, useRegionTree } from '../region/useRegionTree'
import { nameKey } from './libraryNames'
import { useMyLibrary } from './MyLibraryContext'
import styles from './MyLibrariesPage.module.css'

/** Wording [안 문구, 규칙 확정] (flow S10), except the two marked dev placeholders. */
const TEXT = {
  limit: '직접 추가는 20곳까지예요. 하나를 빼면 추가할 수 있어요',
  saveFailed: '저장하지 못했어요. 잠시 뒤 다시 해 주세요',
  // dev placeholder (not in flow): the library was switched off or removed meanwhile
  notFound: '지금은 추가할 수 없는 도서관이에요. 목록을 다시 확인해 주세요',
  disabled: '지금은 쓸 수 없는 도서관이에요',
  empty: '지역을 고르거나 도서관을 추가하세요',
  noMatch: '이름이 맞는 도서관이 없어요',
}

/**
 * S10 `/my-libraries` (cycle 06 flow S10): the regions, the libraries they bring in, and the libraries added by hand.
 * Only for a signed-in browser: while the login check runs it says "불러오는 중", signed out goes to S1 [확정].
 */
export function MyLibrariesPage() {
  const { state } = useAuth()
  if (state.status === 'checking') return <p className={styles.page}>불러오는 중</p>
  if (state.status !== 'signed-in') return <Navigate to="/" replace />
  return <MyLibraries />
}

function MyLibraries() {
  const myLibrary = useMyLibrary()
  const { regions, ready, setRegions } = useRegions()
  const tree = useRegionTree()
  const labels = useRegionLabels(tree.data)
  const [editing, setEditing] = useState(false)

  // the public list with the resolved region ids (ADR-0049)
  const list = useQuery({
    queryKey: ['libraries', regions.residence.join(','), regions.work.join(',')],
    queryFn: ({ signal }) => fetchLibraries(regions.residence, regions.work, signal),
    enabled: myLibrary.status === 'ready' && ready,
    staleTime: 5 * 60_000,
  })

  if (myLibrary.status === 'error' || list.isError) {
    return (
      <div className={styles.page}>
        <Heading />
        <p className={styles.state} role="alert">
          내 도서관을 불러오지 못했어요{' '}
          <button
            type="button"
            className={styles.link}
            onClick={() => (myLibrary.status === 'error' ? myLibrary.retry() : void list.refetch())}
          >
            다시 시도
          </button>
        </p>
      </div>
    )
  }
  if (myLibrary.status !== 'ready' || !list.data) {
    return (
      <div className={styles.page}>
        <Heading />
        <p className={styles.state} role="status">
          불러오는 중
        </p>
      </div>
    )
  }

  const rows = list.data
  const empty = regions.residence.length === 0 && regions.work.length === 0 && myLibrary.libraryIds.length === 0
  return (
    <div className={styles.page}>
      <Heading />
      <OpenToAll rows={rows.filter((row) => row.eligibility === 'OPEN')} />
      {empty && <p className={styles.state}>{TEXT.empty}</p>}

      <RegionSection residence={regions.residence} work={regions.work} labels={labels} onEdit={() => setEditing(true)} />
      <AutoSection
        residence={rows.filter((row) => row.matchedBy.includes('RESIDENCE'))}
        work={rows.filter((row) => row.matchedBy.includes('WORK'))}
      />
      <ChosenSections rows={rows} />

      <RegionModal
        open={editing}
        onOpenChange={setEditing}
        regions={regions}
        savedTo="account"
        onSave={async (next) => {
          await setRegions(next)
          setEditing(false)
        }}
      />
    </div>
  )
}

function Heading() {
  return (
    <header className={styles.head}>
      <h1 className={styles.title}>내 도서관</h1>
      <p className={styles.lead}>"내 도서관" 스위치를 켜면 여기 있는 도서관에서 찾아요.</p>
    </header>
  )
}

function OpenToAll({ rows }: { rows: LibraryEligibilityRow[] }) {
  return (
    <details className={styles.openToAll}>
      <summary>누구나 가입하는 도서관 {rows.length}곳도 늘 내 도서관에 들어 있어요</summary>
      <NameList rows={rows} />
    </details>
  )
}

function Section({ title, aside, children }: { title: string; aside?: ReactNode; children: ReactNode }) {
  const id = useId()
  return (
    <section className={styles.section} aria-labelledby={id}>
      <div className={styles.sectionHead}>
        <h2 id={id} className={styles.sectionTitle}>
          {title}
        </h2>
        {aside}
      </div>
      {children}
    </section>
  )
}

/**
 * "지역 선택": one [변경] for both fields; S2 has its own 거주/직장 tabs (user request 2026-10-05, in place of the
 * separate 거주·직장 sections of the design).
 */
function RegionSection({
  residence,
  work,
  labels,
  onEdit,
}: {
  residence: number[]
  work: number[]
  labels: Map<number, string>
  onEdit: () => void
}) {
  const names = (ids: number[]) =>
    ids.length === 0 ? '고른 지역이 없어요' : ids.map((id) => labels.get(id) ?? '…').join(', ')
  return (
    <Section
      title="지역 선택"
      aside={
        <button type="button" className={styles.link} onClick={onEdit} aria-label="지역 선택 변경">
          변경
        </button>
      }
    >
      <dl className={styles.regionList}>
        <div>
          <dt className={styles.kind}>거주</dt>
          <dd className={styles.regions}>{names(residence)}</dd>
        </div>
        <div>
          <dt className={styles.kind}>직장</dt>
          <dd className={styles.regions}>{names(work)}</dd>
        </div>
      </dl>
    </Section>
  )
}

/** "지역으로 자동": the libraries each field brings in, folded; nothing to remove here (spec R4). */
function AutoSection({ residence, work }: { residence: LibraryEligibilityRow[]; work: LibraryEligibilityRow[] }) {
  return (
    <Section title="지역으로 자동">
      <AutoGroup title="거주 지역으로 자동" rows={residence} />
      <AutoGroup title="직장 지역으로 자동" rows={work} />
    </Section>
  )
}

function AutoGroup({ title, rows }: { title: string; rows: LibraryEligibilityRow[] }) {
  const id = useId()
  return (
    <div className={styles.autoGroup} role="group" aria-labelledby={id}>
      <h3 id={id} className={styles.autoTitle}>
        {title}
      </h3>
      {rows.length === 0 ? (
        <p className={styles.muted}>없어요</p>
      ) : (
        <details className={styles.fold}>
          <summary>{rows.length}곳</summary>
          <NameList rows={rows} />
        </details>
      )}
    </div>
  )
}

function NameList({ rows }: { rows: LibraryEligibilityRow[] }) {
  return (
    <ul className={styles.names}>
      {rows.map((row) => (
        <li key={row.id}>{row.name}</li>
      ))}
    </ul>
  )
}

/** The last two sections: name search with [추가], and the added libraries N/20 with [빼기]. Every press saves at once. */
function ChosenSections({ rows }: { rows: LibraryEligibilityRow[] }) {
  const { libraryIds, maxLibraries, setLibraryIds } = useMyLibrary()
  const [query, setQuery] = useState('')
  const [message, setMessage] = useState<string | null>(null)
  const byId = useMemo(() => new Map(rows.map((row) => [row.id, row])), [rows])
  const full = libraryIds.length >= maxLibraries
  const key = nameKey(query)
  const matches = key ? rows.filter((row) => nameKey(row.name).includes(key)) : []

  const failure = (error: unknown) => {
    if (error instanceof ApiError && error.problem.code === 'library_limit_reached') return TEXT.limit
    if (error instanceof ApiError && error.problem.code === 'library_not_found') return TEXT.notFound
    // 401 after the refresh: the browser is signed out and this screen goes to S1
    return TEXT.saveFailed
  }

  const add = async (id: number) => {
    setMessage(null)
    setLibraryIds((ids) => (ids.includes(id) ? ids : [...ids, id]))
    try {
      await addMyLibrary(id)
    } catch (error) {
      setLibraryIds((ids) => ids.filter((x) => x !== id))
      setMessage(failure(error))
    }
  }

  const remove = async (id: number) => {
    setMessage(null)
    const before = libraryIds
    setLibraryIds((ids) => ids.filter((x) => x !== id))
    try {
      await removeMyLibrary(id)
    } catch (error) {
      setLibraryIds(() => before)
      setMessage(failure(error))
    }
  }

  return (
    <>
      <Section title="직접 도서관 추가">
        <input
          type="search"
          className={styles.search}
          value={query}
          onChange={(event) => setQuery(event.target.value)}
          placeholder="도서관 이름"
          aria-label="도서관 이름으로 찾기"
        />
        {full && <p className={styles.note}>{TEXT.limit}</p>}
        {key && matches.length === 0 && <p className={styles.muted}>{TEXT.noMatch}</p>}
        {matches.length > 0 && (
          <ul className={styles.rows}>
            {matches.map((row) => {
              const added = libraryIds.includes(row.id)
              return (
                <li key={row.id} className={styles.row} data-added={added || undefined}>
                  <span className={styles.name}>{row.name}</span>
                  <button
                    type="button"
                    className={styles.add}
                    disabled={added || full}
                    onClick={() => void add(row.id)}
                    aria-label={added ? `${row.name} 이미 있음` : `${row.name} 추가`}
                  >
                    {added ? '이미 있음' : '추가'}
                  </button>
                </li>
              )
            })}
          </ul>
        )}
      </Section>

      <Section
        title="직접 추가한 도서관"
        aside={
          <span className={styles.count}>
            {libraryIds.length}/{maxLibraries}
          </span>
        }
      >
        {message && (
          <p className={styles.error} role="alert">
            {message}
          </p>
        )}
        {libraryIds.length === 0 ? (
          <p className={styles.muted}>아직 없어요</p>
        ) : (
          <ul className={styles.rows}>
            {libraryIds.map((id) => {
              const row = byId.get(id)
              const name = row?.name ?? TEXT.disabled
              return (
                <li key={id} className={styles.row} data-disabled={row ? undefined : true}>
                  <span className={styles.name}>{name}</span>
                  <button
                    type="button"
                    className={styles.remove}
                    onClick={() => void remove(id)}
                    aria-label={`${row?.name ?? '쓸 수 없는 도서관'} 빼기`}
                  >
                    빼기
                  </button>
                </li>
              )
            })}
          </ul>
        )}
      </Section>
    </>
  )
}

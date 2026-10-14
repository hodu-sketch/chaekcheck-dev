import { useLocation, useNavigate } from 'react-router'
import type { DocumentVersionInfo } from '../api/types'
import styles from './VersionPicker.module.css'
import { versionPeriod } from './versionPeriod'

/**
 * The date picker under a title (cycle 16, flow "이전 버전 고르기"): a native select, so a phone opens its own
 * chooser. Hidden when there is only one version. Choosing a version opens the same screen at `?version=n`; the
 * current version is the address without `version`. Router state (where "목록으로" goes back to) is kept.
 */
export function VersionPicker({ versions, shown }: { versions: DocumentVersionInfo[]; shown: number }) {
  const navigate = useNavigate()
  const location = useLocation()
  if (versions.length < 2) return null

  const choose = (value: string) => {
    const chosen = versions.find((version) => String(version.number) === value)
    if (!chosen) return
    const next = new URLSearchParams(location.search)
    if (chosen.supersededOn === null) next.delete('version')
    else next.set('version', String(chosen.number))
    const search = next.size ? `?${next}` : ''
    void navigate({ pathname: location.pathname, search }, { state: location.state })
  }

  return (
    <select
      className={styles.picker}
      aria-label="적용 기간"
      value={String(shown)}
      onChange={(event) => choose(event.target.value)}
    >
      {versions.map((version) => (
        <option key={version.number} value={String(version.number)}>
          {versionPeriod(version)}
        </option>
      ))}
    </select>
  )
}

/** The small "이전 버전" mark next to a title while an earlier version is shown (no yellow, C8). */
export function EarlierVersionMark() {
  return <span className={styles.earlier}>이전 버전</span>
}

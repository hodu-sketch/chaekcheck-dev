import { useEffect, useState } from 'react'
import { fetchLatestJoinSurvey } from '../../api/client'
import { Markdown } from '../../components/Markdown'
import { EarlierVersionMark, VersionPicker } from '../../components/VersionPicker'
import styles from '../auth/LegalPage.module.css'
import { DocumentState } from '../sitecontent/DocumentState'
import { useSiteDocument } from '../sitecontent/siteContent'
import { surveyMonthLabel } from './aboutText'

/**
 * S12 `/about` (cycle 07, ADR-0059): what 책첵 is, whose logos and names these are, and the contact e-mail. Since
 * cycle 16 the text is the `about` document from `GET /api/v1/documents/about` (ADR-0098), drawn as markdown: title,
 * the version's date with the date picker under it, then the text. The same white card as S8·S9. Open without login.
 *
 * Cycle 13 (spec R5, ADR-0093): the month the join rules were surveyed is not part of the text; the screen adds it
 * right under the whole text (cycle 16 C6, user decision 2026-10-14). While it loads, with no date yet, or on an error,
 * the line is left out.
 *
 * Cycle 14 (ADR-0091): the picks paragraph is now in the text (cycle 16 `drafts/about.md`).
 * TODO(확인 필요): 배포 전 국립중앙도서관 표지 이용 조건 확인
 */
export function AboutPage() {
  const [surveyMonth, setSurveyMonth] = useState<string | null>(null)
  const { version, query } = useSiteDocument('about')
  const doc = query.data

  useEffect(() => {
    const controller = new AbortController()
    fetchLatestJoinSurvey(controller.signal)
      .then((latest) => setSurveyMonth(latest.surveyedOn ? surveyMonthLabel(latest.surveyedOn) : null))
      .catch(() => {
        if (controller.signal.aborted) return
        setSurveyMonth(null)
      })
    return () => controller.abort()
  }, [])

  return (
    <article className={styles.page} aria-labelledby="about-title">
      <header className={styles.head}>
        <h1 id="about-title" className={styles.title}>
          서비스 안내
          {doc && !doc.version.current && <EarlierVersionMark />}
        </h1>
        {doc && (
          <div className={styles.meta}>
            <span>{doc.version.effectiveOn}</span>
            <VersionPicker versions={doc.versions} shown={doc.version.number} />
          </div>
        )}
      </header>
      <DocumentState version={version} query={query}>
        {(loaded) => (
          <>
            <Markdown>{loaded.version.body}</Markdown>
            {surveyMonth && <p className={styles.note}>가입 조건은 {surveyMonth}에 조사한 내용입니다.</p>}
          </>
        )}
      </DocumentState>
    </article>
  )
}

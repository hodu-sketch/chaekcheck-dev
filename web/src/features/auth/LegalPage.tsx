import { BackLink } from '../../components/BackLink'
import { ChangeSummary } from '../../components/ChangeSummary'
import { Markdown } from '../../components/Markdown'
import { EarlierVersionMark, VersionPicker } from '../../components/VersionPicker'
import { DocumentState } from '../sitecontent/DocumentState'
import { useSiteDocument } from '../sitecontent/siteContent'
import styles from './LegalPage.module.css'

/**
 * S8 `/terms` and S9 `/privacy`: title, "시행 YYYY-MM-DD" with the date picker on the same line, "바뀐 점 보기" when the
 * version has one, then the text. Since cycle 16 the text and versions come from `GET /api/v1/documents/{key}`
 * (ADR-0098) and are drawn as markdown without HTML (C2). `/privacy` is open whether login is on or off; `/terms` only
 * while it is on (ADR-0100, `main.tsx`).
 */
export function LegalPage({ document }: { document: 'terms' | 'privacy' }) {
  const { version, query } = useSiteDocument(document === 'terms' ? 'terms-of-service' : 'privacy-policy')
  const title = document === 'terms' ? '이용약관' : '개인정보 처리방침'
  const doc = query.data
  return (
    <article className={styles.page} aria-labelledby="legal-title">
      <header className={styles.head}>
        <h1 id="legal-title" className={styles.title}>
          {title}
          {doc && !doc.version.current && <EarlierVersionMark />}
        </h1>
        {doc && (
          <div className={styles.meta}>
            <span>시행 {doc.version.effectiveOn}</span>
            <VersionPicker versions={doc.versions} shown={doc.version.number} />
          </div>
        )}
        {doc && <ChangeSummary key={doc.version.number} summary={doc.version.changeSummary} />}
      </header>
      <DocumentState version={version} query={query}>
        {(loaded) => <Markdown>{loaded.version.body}</Markdown>}
      </DocumentState>
      <BackLink />
    </article>
  )
}

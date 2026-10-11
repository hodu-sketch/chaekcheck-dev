import { BackLink } from '../../components/BackLink'
import documentVersions from './documentVersions.json'
import { NOT_REVIEWED, PRIVACY_POLICY_SECTIONS, TERMS_OF_SERVICE_BODY } from './legalText'
import styles from './LegalPage.module.css'

/**
 * S8 `/terms` and S9 `/privacy`: title, version and effective date, full text. Open without login (R10). Text is
 * drawn only as React text, never as HTML (C22). The versions come from `documentVersions.json`, which a backend
 * test keeps equal to `chaekcheck.auth.documents` (C27); the version is the effective date (`YYYY-MM-DD`).
 */
export function LegalPage({ document }: { document: 'terms' | 'privacy' }) {
  const version = document === 'terms' ? documentVersions.termsOfService : documentVersions.privacyCollection
  const title = document === 'terms' ? '이용약관' : '개인정보 처리방침'
  return (
    <article className={styles.page} aria-labelledby="legal-title">
      <header className={styles.head}>
        <h1 id="legal-title" className={styles.title}>
          {title}
        </h1>
        <p className={styles.meta}>
          버전 {version} · 시행일 {version}
          <span className={styles.draft}>{NOT_REVIEWED}</span>
        </p>
      </header>
      {document === 'terms' ? (
        <div className={styles.body}>
          {TERMS_OF_SERVICE_BODY.map((paragraph) => (
            <p key={paragraph}>{paragraph}</p>
          ))}
        </div>
      ) : (
        <div className={styles.body}>
          {PRIVACY_POLICY_SECTIONS.map((section) => (
            <section key={section.heading}>
              <h2 className={styles.heading}>{section.heading}</h2>
              {section.paragraphs.map((paragraph) => (
                <p key={paragraph}>{paragraph}</p>
              ))}
            </section>
          ))}
        </div>
      )}
      <BackLink />
    </article>
  )
}

import { Fragment } from 'react'
import { Link } from 'react-router'
import { useOptionalAuth } from '../features/auth/AuthContext'
import styles from './Footer.module.css'

/**
 * AC-12 (cycle 01): the data source line. Cycle 16 (spec R6, ADR-0100): one link line "공지사항 · 이용약관 ·
 * 개인정보 처리방침" (책첵이란? is next to the logo only, user 2026-10-20: "하단에 책첵이란 이거 빼줘 위에도 있자나").
 * 이용약관 only while login is on; the privacy policy always.
 */
export function Footer() {
  const auth = useOptionalAuth()
  const showTerms = auth !== null && (auth.state.status === 'signed-in' || auth.state.status === 'signed-out')
  const links = [
    { to: '/notices', label: '공지사항' },
    ...(showTerms ? [{ to: '/terms', label: '이용약관' }] : []),
    { to: '/privacy', label: '개인정보 처리방침' },
  ]
  return (
    <footer className={styles.footer}>
      <div className={styles.inner}>
        <p>수량은 각 도서관의 공개 검색 결과 기준이며, 도서관 사이트와 잠시 다를 수 있어요.</p>
        <nav className={styles.links} aria-label="안내">
          {links.map((link, index) => (
            <Fragment key={link.to}>
              {index > 0 && (
                <span className={styles.dot} aria-hidden="true">
                  ·
                </span>
              )}
              <Link to={link.to} className={styles.link}>
                {link.label}
              </Link>
            </Fragment>
          ))}
        </nav>
      </div>
    </footer>
  )
}

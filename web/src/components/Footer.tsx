import { Link } from 'react-router'
import { useOptionalAuth } from '../features/auth/AuthContext'
import styles from './Footer.module.css'

/**
 * AC-12: data source and contact. Terms and privacy links only while login is on (cycle 05 [확정]). "서비스 안내" on
 * every screen (cycle 07, AC-21).
 */
export function Footer() {
  const auth = useOptionalAuth()
  const showLegal = auth !== null && (auth.state.status === 'signed-in' || auth.state.status === 'signed-out')
  return (
    <footer className={styles.footer}>
      <div className={styles.inner}>
        <p>수량은 각 도서관의 공개 검색 결과 기준이며, 도서관 사이트와 잠시 다를 수 있어요.</p>
        <div className={styles.links}>
          <Link to="/about" className={styles.link}>
            서비스 안내
          </Link>
          {showLegal && (
            <>
              <Link to="/terms" className={styles.link}>
                이용약관
              </Link>
              <Link to="/privacy" className={styles.link}>
                개인정보 처리방침
              </Link>
            </>
          )}
          {/* TODO: contact is not decided yet (handoff: 연락처 값은 사용자에게 받은 뒤 채움, AC-12) */}
          <p className={styles.contact}>문의: 준비 중</p>
        </div>
      </div>
    </footer>
  )
}

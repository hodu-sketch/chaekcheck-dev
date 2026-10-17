import { useLocation, useNavigate } from 'react-router'
import { ChevronLeft } from './icons'
import styles from './BackLink.module.css'

/**
 * "← 돌아가기" at the bottom right (cycle 08, flow.md 검토 #7): the previous screen, or the start screen (S1) when the
 * page was opened directly from its address. React Router gives the first entry of a visit the key "default".
 */
export function BackLink() {
  const navigate = useNavigate()
  const { key } = useLocation()
  const back = () => {
    if (key === 'default') void navigate('/')
    else void navigate(-1)
  }
  return (
    <div className={styles.row}>
      {/* the app's chevron like 「‹ 목록으로」, not the ← glyph (user 2026-10-17) */}
      <button type="button" className={styles.back} onClick={back}>
        <ChevronLeft size={7} /> 돌아가기
      </button>
    </div>
  )
}

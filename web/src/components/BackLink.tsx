import { useLocation, useNavigate } from 'react-router'
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
      <button type="button" className={styles.back} onClick={back}>
        ← 돌아가기
      </button>
    </div>
  )
}

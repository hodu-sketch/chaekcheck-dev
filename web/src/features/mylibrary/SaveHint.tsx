import { useState } from 'react'
import { Cross } from '../../components/icons'
import { useOptionalAuth } from '../auth/AuthContext'
import { LoginDialog } from '../auth/LoginDialog'
import { loadPrefs, savePrefs } from '../search/searchSettings'
import styles from './SaveHint.module.css'

/** Wording [확정] (cycle 22 문구표 "저장 안내", R9-1). */
const TEXT = {
  hint: '이 브라우저에만 저장됐어요. 로그인하면 다른 기기에서도 그대로예요',
  login: '카카오 로그인',
  close: '저장 안내 닫기',
}

interface Props {
  /**
   * The part this hint sits under has just saved something in this browser (a region, an added library). The caller
   * sets it after its own save; the hint itself decides whether it may show.
   */
  saved: boolean
}

/**
 * "이 브라우저에만 저장됐어요 …" (cycle 22 R9): one line right under the part that saved, before login only. × hides
 * it in this browser for good (`saveHintClosed`, D5). [카카오 로그인] opens the header's login dialog (R9-1: the same
 * flow; back from it the cycle 07 S11 import follows). Nothing while signed in, while the login check runs, or with
 * login switched off (there is nothing to log in to).
 */
export function SaveHint({ saved }: Props) {
  const auth = useOptionalAuth()
  const [closed, setClosed] = useState(() => loadPrefs().saveHintClosed)
  const [loginOpen, setLoginOpen] = useState(false)
  const signedOut = auth?.state.status === 'signed-out'

  if (!signedOut || closed || !saved) return null

  const close = () => {
    savePrefs({ saveHintClosed: true })
    setClosed(true)
  }

  return (
    <>
      <p className={styles.hint} role="status">
        <span className={styles.text}>{TEXT.hint}</span>
        <button type="button" className={styles.login} onClick={() => setLoginOpen(true)}>
          {TEXT.login}
        </button>
        <button type="button" className={styles.close} onClick={close} aria-label={TEXT.close}>
          <Cross size={12} />
        </button>
      </p>
      <LoginDialog open={loginOpen} onOpenChange={setLoginOpen} />
    </>
  )
}

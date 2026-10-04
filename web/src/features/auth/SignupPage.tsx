import { useCallback, useEffect, useRef, useState } from 'react'
import { useNavigate } from 'react-router'
import { cancelSignup, getSignup, postSignup } from '../../api/auth'
import { ApiError } from '../../api/client'
import type { ConsentInput, SignupInfo } from '../../api/types'
import { useAuth } from './AuthContext'
import { SIGNUP_SUCCESS_MESSAGE, takeReturnPath } from './authResult'
import {
  COLLECTION_NOTICE,
  NOT_REVIEWED,
  PRIVACY_COLLECTION_BODY,
  TERMS_OF_SERVICE_BODY,
} from './legalText'
import { useNotice } from './NoticeContext'
import styles from './SignupPage.module.css'

type Box = 'TERMS_OF_SERVICE' | 'PRIVACY_COLLECTION' | 'AGE_OVER_14'

const BOXES: ReadonlyArray<{ type: Box; label: string; body?: ReadonlyArray<string> }> = [
  { type: 'TERMS_OF_SERVICE', label: '이용약관에 동의합니다', body: TERMS_OF_SERVICE_BODY },
  { type: 'PRIVACY_COLLECTION', label: '개인정보 수집·이용에 동의합니다', body: PRIVACY_COLLECTION_BODY },
  { type: 'AGE_OVER_14', label: '만 14세 이상입니다' },
]

const NONE: Record<Box, boolean> = { TERMS_OF_SERVICE: false, PRIVACY_COLLECTION: false, AGE_OVER_14: false }

/**
 * S6 `/signup`: consent for a provider account seen for the first time (R2). No account exists before 가입. Opened
 * only right after the provider; without a pending sign-up (404) the browser goes to S1 (flow.md).
 */
export function SignupPage() {
  const navigate = useNavigate()
  const { reload, markSignedOut } = useAuth()
  const { showNotice } = useNotice()
  const [info, setInfo] = useState<SignupInfo | null>(null)
  const [checked, setChecked] = useState<Record<Box, boolean>>(NONE)
  const [touched, setTouched] = useState(false)
  const [open, setOpen] = useState<Box | null>(null)
  const [saving, setSaving] = useState(false)
  const [message, setMessage] = useState<string | null>(null)
  const started = useRef(false)

  const expired = useCallback(() => {
    showNotice('시간이 지났어요. 처음부터 다시 로그인해 주세요')
    navigate('/', { replace: true })
  }, [navigate, showNotice])

  useEffect(() => {
    if (started.current) return
    started.current = true
    getSignup()
      .then(setInfo)
      .catch(() => navigate('/', { replace: true }))
  }, [navigate])

  const all = BOXES.every((box) => checked[box.type])

  const toggle = (type: Box) => {
    setTouched(true)
    setChecked((current) => ({ ...current, [type]: !current[type] }))
  }

  const toggleAll = () => {
    setTouched(true)
    setChecked(all ? NONE : { TERMS_OF_SERVICE: true, PRIVACY_COLLECTION: true, AGE_OVER_14: true })
  }

  const submit = async () => {
    if (info === null || !all) return
    setSaving(true)
    setMessage(null)
    const consents: ConsentInput[] = [
      ...info.documents.map((document) => ({ type: document.type, version: document.version })),
      { type: 'AGE_OVER_14' },
    ]
    try {
      await postSignup(consents)
      await reload()
      showNotice(SIGNUP_SUCCESS_MESSAGE)
      navigate(takeReturnPath(), { replace: true })
    } catch (error) {
      const code = error instanceof ApiError ? error.problem.code : null
      if (code === 'document_version_mismatch') {
        // C24: say so, read the new versions, show the boxes empty again
        setMessage('약관이 바뀌었어요. 다시 확인해 주세요')
        setChecked(NONE)
        setTouched(false)
        getSignup().then(setInfo).catch(expired)
      } else if (code === 'signup_not_found') {
        expired()
      } else if (code === 'validation_error') {
        setTouched(true)
      } else {
        setMessage('가입하지 못했어요. 잠시 뒤 다시 해 주세요')
      }
    } finally {
      setSaving(false)
    }
  }

  const cancel = async () => {
    setSaving(true)
    try {
      await cancelSignup()
    } catch {
      // The pending sign-up is gone either way (404) or the server will drop it in 10 minutes
    }
    markSignedOut()
    navigate('/', { replace: true })
  }

  if (info === null) return null

  return (
    <section className={styles.page} aria-labelledby="signup-title">
      <h1 id="signup-title" className={styles.title}>
        가입 동의
      </h1>
      <p className={styles.lead}>처음 로그인하셨네요. 아래 내용을 확인하고 동의하면 가입이 끝나요.</p>

      <table className={styles.notice}>
        <caption className={styles.caption}>개인정보 수집·이용 안내</caption>
        <tbody>
          {COLLECTION_NOTICE.map((row) => (
            <tr key={row.label}>
              <th scope="row">{row.label}</th>
              <td>
                {row.text}
                {!row.reviewed && <span className={styles.draft}>{NOT_REVIEWED}</span>}
              </td>
            </tr>
          ))}
        </tbody>
      </table>

      <div className={styles.boxes}>
        <label className={styles.allRow}>
          <input type="checkbox" checked={all} onChange={toggleAll} />
          <span>모두 동의</span>
        </label>
        {BOXES.map((box) => (
          <div key={box.type} className={styles.row}>
            <label className={styles.boxLabel}>
              <input type="checkbox" checked={checked[box.type]} onChange={() => toggle(box.type)} />
              <span>
                {box.label} <span className={styles.required}>(필수)</span>
              </span>
            </label>
            {box.body && (
              <button
                type="button"
                className={styles.more}
                aria-expanded={open === box.type}
                onClick={() => setOpen(open === box.type ? null : box.type)}
              >
                전문 보기
              </button>
            )}
            {touched && !checked[box.type] && <p className={styles.missing}>필수 항목이에요</p>}
            {box.body && open === box.type && (
              <div className={styles.body}>
                {box.body.map((paragraph) => (
                  <p key={paragraph}>{paragraph}</p>
                ))}
              </div>
            )}
          </div>
        ))}
      </div>

      {message && (
        <p className={styles.message} role="alert">
          {message}
        </p>
      )}

      <div className={styles.actions}>
        <button type="button" className={styles.cancel} onClick={cancel} disabled={saving}>
          취소
        </button>
        <button type="button" className={styles.submit} onClick={submit} disabled={!all || saving}>
          {saving ? '가입하는 중' : '가입'}
        </button>
      </div>
    </section>
  )
}

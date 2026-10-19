import { useQuery } from '@tanstack/react-query'
import { Dialog } from 'radix-ui'
import { useState, type ReactNode } from 'react'
import { useLocation } from 'react-router'
import { fetchProviders, loginStartUrl } from '../../api/auth'
import type { LoginProvider } from '../../api/types'
import kakaoSymbol from '../../assets/login/kakao-symbol.svg'
import naverLogin from '../../assets/login/naver-login.png'
import { BottomSheet } from '../../components/BottomSheet'
import { CheckMark } from '../../components/icons'
import { useMediaQuery } from '../../lib/useMediaQuery'
import { rememberReturnPath } from './authResult'
import styles from './LoginDialog.module.css'

interface Props {
  open: boolean
  onOpenChange: (open: boolean) => void
  /** Page navigation; replaced in tests. */
  navigate?: (url: string) => void
}

/** The window's wording (user 2026-10-19, from the user's picture). */
export const LOGIN_TEXT = {
  eyebrow: '책첵 로그인',
  headline: ['내 검색 설정을', '다른 기기에서도 그대로.'],
  description: '책첵에 로그인하면 저장한 도서관과 지역 설정을 같은 계정으로 사용할 수 있어요.',
  benefits: ['내가 정한 검색 범위 저장', 'PC와 휴대폰에서 같은 설정 사용'],
  separateTitle: '도서관 회원가입과는 별개예요.',
  separateText: '전자책을 빌리려면 각 도서관의 가입·인증이 필요해요.',
  kakao: '카카오로 계속하기',
  signupNote: '처음 이용하면 책첵 계정 가입으로 이어져요.',
  skip: '로그인 없이 계속하기',
  failed: '지금은 로그인할 수 없어요. 잠시 뒤 다시 해 주세요',
} as const

/**
 * S5: the login window (redesigned 2026-10-19 after the user's picture): a violet eyebrow, a small lavender mark, the
 * two-line headline, what logging in keeps, a grey note that it is not a library membership, then the provider
 * buttons and "로그인 없이 계속하기", which only closes. The buttons follow the providers' guides (plan research.md
 * 4절): Kakao's official symbol on #FEE500 with the label in black at 85%, Naver's official button image under it.
 * Only the providers the server offers are shown (AC-22, `GET /api/v1/auth/providers`). PC: a window about 26rem
 * wide with × at the top right. Phones: the shared bottom sheet with a handle and ⓧ, and back closes only the sheet
 * (user 2026-10-12).
 */
export function LoginDialog({ open, onOpenChange, navigate = (url) => window.location.assign(url) }: Props) {
  const location = useLocation()
  const providers = useQuery({ queryKey: ['auth', 'providers'], queryFn: fetchProviders, enabled: open, staleTime: Infinity })
  const [leaving, setLeaving] = useState(false)
  const phone = useMediaQuery('(width <= 640px)')

  const start = (provider: LoginProvider) => {
    rememberReturnPath(location.pathname + location.search)
    setLeaving(true)
    navigate(loginStartUrl(provider, 'login'))
  }

  const actions = (
    <div className={styles.actions}>
      <div className={styles.buttons}>
        {providers.data?.includes('KAKAO') && (
          <button type="button" className={styles.kakao} disabled={leaving} onClick={() => start('KAKAO')}>
            <img src={kakaoSymbol} alt="" className={styles.kakaoSymbol} />
            <span className={styles.kakaoLabel}>{LOGIN_TEXT.kakao}</span>
          </button>
        )}
        {providers.data?.includes('NAVER') && (
          <button type="button" className={styles.naver} disabled={leaving} onClick={() => start('NAVER')}>
            <img src={naverLogin} alt="네이버 로그인" className={styles.naverImage} />
          </button>
        )}
        {providers.isError && <p className={styles.error}>{LOGIN_TEXT.failed}</p>}
      </div>
      <p className={styles.signupNote}>{LOGIN_TEXT.signupNote}</p>
      <button type="button" className={styles.skip} onClick={() => onOpenChange(false)}>
        {LOGIN_TEXT.skip}
      </button>
    </div>
  )

  if (phone) {
    return (
      <BottomSheet open={open} onOpenChange={onOpenChange} title={LOGIN_TEXT.eyebrow} closeButton closeOnBack>
        <div className={styles.sheet}>
          <Body
            eyebrow={
              // the sheet's name is read from its hidden title; this line only shows it
              <p className={styles.eyebrow} aria-hidden="true">
                {LOGIN_TEXT.eyebrow}
              </p>
            }
            description={<p className={styles.description}>{LOGIN_TEXT.description}</p>}
          />
          {actions}
        </div>
      </BottomSheet>
    )
  }
  return (
    <Dialog.Root open={open} onOpenChange={onOpenChange}>
      <Dialog.Portal>
        <Dialog.Overlay className={styles.overlay} />
        <Dialog.Content className={styles.content}>
          <Dialog.Close className={styles.close} aria-label="닫기">
            <svg width="14" height="14" viewBox="0 0 14 14" aria-hidden="true" focusable="false">
              <path d="M2 2l10 10M12 2L2 12" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" />
            </svg>
          </Dialog.Close>
          <Body
            eyebrow={
              <Dialog.Title asChild>
                <p className={styles.eyebrow}>{LOGIN_TEXT.eyebrow}</p>
              </Dialog.Title>
            }
            description={
              <Dialog.Description className={styles.description}>{LOGIN_TEXT.description}</Dialog.Description>
            }
          />
          {actions}
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  )
}

/** The text part, the same in the window and the sheet. */
function Body({ eyebrow, description }: { eyebrow: ReactNode; description: ReactNode }) {
  return (
    <div className={styles.body}>
      {eyebrow}
      <span className={styles.mark} aria-hidden="true">
        <svg width="22" height="22" viewBox="0 0 22 22" focusable="false">
          <rect x="3" y="3" width="16" height="16" rx="4" fill="none" stroke="currentColor" strokeWidth="2" />
          <rect x="7.5" y="7.5" width="7" height="7" rx="1.8" fill="currentColor" />
        </svg>
      </span>
      <h2 className={styles.headline}>
        {LOGIN_TEXT.headline[0]}
        <br />
        {LOGIN_TEXT.headline[1]}
      </h2>
      {description}
      <ul className={styles.benefits}>
        {LOGIN_TEXT.benefits.map((line) => (
          <li key={line}>
            <CheckMark size={13} className={styles.check} />
            {line}
          </li>
        ))}
      </ul>
      <div className={styles.separate}>
        <p className={styles.separateTitle}>{LOGIN_TEXT.separateTitle}</p>
        <p>{LOGIN_TEXT.separateText}</p>
      </div>
    </div>
  )
}

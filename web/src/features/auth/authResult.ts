/**
 * Result codes the server puts in `/auth/complete?result=` (design-delta "결과 코드", ADR-0045) and the notice
 * each one shows. Only codes in this table are shown, always with the fixed wording; the code itself is never put on
 * screen (C21). `provider` is read only as `naver` or `kakao`.
 */

const PROVIDER_NAME: Record<string, string> = { naver: '네이버', kakao: '카카오' }

/** What `/auth/complete` does with a result. */
export type AuthResult =
  | { kind: 'signed-in' }
  | { kind: 'withdraw' }
  | { kind: 'notice'; message: string }
  | { kind: 'ignore' }

export function readAuthResult(result: string | null, provider: string | null): AuthResult {
  const providerName = provider !== null ? PROVIDER_NAME[provider] : undefined
  // Without a valid provider the name is left out (dev choice; the table always sends one)
  const side = providerName ? `${providerName} 쪽` : '로그인 서비스 쪽'
  switch (result) {
    case 'login_success':
      return { kind: 'signed-in' }
    case 'login_cancelled':
      return { kind: 'notice', message: '로그인을 취소했어요' }
    case 'login_provider_error':
      return { kind: 'notice', message: `${side} 문제로 로그인하지 못했어요. 잠시 뒤 다시 해 주세요` }
    case 'login_expired':
      return { kind: 'notice', message: '로그인 시간이 지났어요. 다시 해 주세요' }
    case 'login_state_mismatch':
      return { kind: 'notice', message: '안전하지 않은 로그인 요청이라 멈췄어요. 로그인 버튼을 눌러 다시 해 주세요' }
    case 'withdraw_reauthenticated':
      return { kind: 'withdraw' }
    case 'withdraw_account_mismatch':
      return { kind: 'notice', message: '가입한 계정으로 확인해 주세요' }
    case 'withdraw_cancelled':
      return { kind: 'notice', message: '탈퇴를 취소했어요' }
    case 'withdraw_provider_error':
      return { kind: 'notice', message: `${side} 문제로 본인 확인을 하지 못했어요. 잠시 뒤 다시 해 주세요` }
    case 'withdraw_login_expired':
      return { kind: 'notice', message: '로그인이 풀렸어요. 다시 로그인한 뒤 탈퇴해 주세요' }
    default:
      return { kind: 'ignore' }
  }
}

const RETURN_KEY = 'chaekcheck.auth.returnTo'

/** A path inside this site only: starts with `/` but not `//` (C21). Anything else is `/`. */
export function safeReturnPath(path: string | null): string {
  if (path === null || !path.startsWith('/') || path.startsWith('//') || path.startsWith('/\\')) return '/'
  return path
}

/** The address to come back to after the provider (path + search), kept in sessionStorage only (C13). */
export function rememberReturnPath(path: string): void {
  try {
    sessionStorage.setItem(RETURN_KEY, path)
  } catch {
    // storage blocked: the user comes back to the start screen
  }
}

/** Reads and forgets the remembered address. */
export function takeReturnPath(): string {
  let path: string | null = null
  try {
    path = sessionStorage.getItem(RETURN_KEY)
    sessionStorage.removeItem(RETURN_KEY)
  } catch {
    // storage blocked
  }
  return safeReturnPath(path)
}

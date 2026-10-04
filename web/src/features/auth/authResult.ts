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
    case 'withdraw_request_expired':
      return { kind: 'notice', message: '본인 확인 시간이 지났어요. 탈퇴를 다시 눌러 주세요' }
    case 'withdraw_state_mismatch':
      return { kind: 'notice', message: '안전하지 않은 요청이라 멈췄어요. 탈퇴를 다시 눌러 주세요' }
    case 'withdraw_login_expired':
      return { kind: 'notice', message: '로그인이 풀렸어요. 다시 로그인한 뒤 탈퇴해 주세요' }
    default:
      return { kind: 'ignore' }
  }
}

/**
 * `signup_success` (cycle 06, AC-17): shown once on S1 right after the first sign-up. Sign-up ends with the web's own
 * `POST /api/v1/auth/signup`, not with a server redirect, so this is web state, not a server result code (user
 * decision 2026-10-04). Signing in again (`login_success`) shows nothing.
 */
export const SIGNUP_SUCCESS_MESSAGE = '가입했어요'

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

/**
 * The notice after `DELETE /api/v1/me` did not delete (flow.md S7 "탈퇴가 멈춤", handoff step 14). 401 comes here only
 * after the refresh failed. Both 403 codes get the same wording (C25).
 */
export function withdrawFailureMessage(error: unknown): string {
  if (error instanceof TypeError) return '인터넷 연결을 확인하고 다시 해 주세요'
  const status = typeof error === 'object' && error !== null && 'status' in error ? (error as { status: number }).status : 0
  if (status === 401) return '로그인이 풀렸어요. 다시 로그인한 뒤 탈퇴해 주세요'
  if (status === 403) return '본인 확인 시간이 지났어요. 탈퇴를 다시 눌러 주세요'
  return 'chaekcheck 서버 문제로 탈퇴하지 못했어요. 잠시 뒤 다시 해 주세요'
}

/** The provider's name in the S7 line "…와 chaekcheck의 연결도 끊어요" (the only place a provider name shows, C33). */
export function providerName(provider: 'NAVER' | 'KAKAO'): string {
  return provider === 'NAVER' ? '네이버' : '카카오'
}

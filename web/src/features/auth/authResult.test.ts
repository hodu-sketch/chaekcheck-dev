import { afterEach, describe, expect, it } from 'vitest'
import { readAuthResult, rememberReturnPath, safeReturnPath, takeReturnPath } from './authResult'

afterEach(() => sessionStorage.clear())

describe('result codes (design-delta "결과 코드")', () => {
  it.each([
    ['login_cancelled', null, '로그인을 취소했어요'],
    ['login_provider_error', 'naver', '네이버 쪽 문제로 로그인하지 못했어요. 잠시 뒤 다시 해 주세요'],
    ['login_provider_error', 'kakao', '카카오 쪽 문제로 로그인하지 못했어요. 잠시 뒤 다시 해 주세요'],
    ['login_expired', null, '로그인 시간이 지났어요. 다시 해 주세요'],
    ['login_state_mismatch', null, '안전하지 않은 로그인 요청이라 멈췄어요. 로그인 버튼을 눌러 다시 해 주세요'],
    ['withdraw_account_mismatch', null, '가입한 계정으로 확인해 주세요'],
    ['withdraw_cancelled', null, '탈퇴를 취소했어요'],
    ['withdraw_provider_error', 'kakao', '카카오 쪽 문제로 본인 확인을 하지 못했어요. 잠시 뒤 다시 해 주세요'],
    ['withdraw_login_expired', null, '로그인이 풀렸어요. 다시 로그인한 뒤 탈퇴해 주세요'],
    ['withdraw_request_expired', null, '본인 확인 시간이 지났어요. 탈퇴를 다시 눌러 주세요'],
    ['withdraw_state_mismatch', null, '안전하지 않은 요청이라 멈췄어요. 탈퇴를 다시 눌러 주세요'],
  ])('%s (%s) shows its fixed wording', (result, provider, message) => {
    expect(readAuthResult(result, provider)).toEqual({ kind: 'notice', message })
  })

  it('success and withdrawal are not notices', () => {
    expect(readAuthResult('login_success', null)).toEqual({ kind: 'signed-in' })
    expect(readAuthResult('withdraw_reauthenticated', null)).toEqual({ kind: 'withdraw' })
  })

  it('an unknown code is ignored and never shown', () => {
    expect(readAuthResult('<script>alert(1)</script>', null)).toEqual({ kind: 'ignore' })
    expect(readAuthResult(null, null)).toEqual({ kind: 'ignore' })
  })

  it('a provider other than naver or kakao is not shown', () => {
    const result = readAuthResult('login_provider_error', 'google')
    expect(result).toEqual({
      kind: 'notice',
      message: '로그인 서비스 쪽 문제로 로그인하지 못했어요. 잠시 뒤 다시 해 주세요',
    })
  })
})

describe('return address (C21)', () => {
  it.each(['//evil.example', 'https://evil.example/', '/\\evil.example', 'javascript:alert(1)', '', null])(
    '%s goes to /',
    (path) => {
      expect(safeReturnPath(path)).toBe('/')
    },
  )

  it('a path inside the site is kept with its search', () => {
    expect(safeReturnPath('/search?title=%EC%82%AC%EB%9E%91')).toBe('/search?title=%EC%82%AC%EB%9E%91')
  })

  it('is kept in sessionStorage once and then forgotten', () => {
    rememberReturnPath('/books/12')
    expect(takeReturnPath()).toBe('/books/12')
    expect(takeReturnPath()).toBe('/')
  })
})

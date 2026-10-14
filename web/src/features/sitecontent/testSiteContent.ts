import { vi } from 'vitest'
import type { Notice, NoticeList, SiteDocument } from '../../api/types'

/** Fake API answers for the cycle 16 screen tests (Vitest only). */

export function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

export function problem(status: number, code: string): Response {
  return json(status, { type: 'about:blank', title: 'Error', status, code })
}

/**
 * Stubs `fetch`: `answers` maps "path?query" (GET) or "METHOD path" to a response. `/api/v1/me` is 401 (login on,
 * signed out) unless given. Anything else fails the test.
 */
export function fakeServer(answers: Record<string, () => Response | Promise<Response>>) {
  const fetchMock = vi.fn(async (url: string, init?: RequestInit) => {
    const method = (init?.method ?? 'GET').toUpperCase()
    const answer = answers[`${method} ${url}`] ?? (method === 'GET' ? answers[url] : undefined)
    if (answer) return answer()
    if (url === '/api/v1/me' || url === '/api/v1/auth/refresh') return problem(401, 'unauthorized')
    throw new Error(`unexpected ${method} ${url}`)
  })
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

/** The URLs asked so far. */
export function calledUrls(fetchMock: ReturnType<typeof fakeServer>): string[] {
  return fetchMock.mock.calls.map(([url]) => url)
}

export function documentOf(
  key: SiteDocument['key'],
  body: string,
  extra: Partial<SiteDocument['version']> = {},
  versions: SiteDocument['versions'] = [{ number: 1, effectiveOn: '2026-10-04', supersededOn: null }],
): SiteDocument {
  return {
    key,
    version: { number: 1, effectiveOn: '2026-10-04', current: true, body, changeSummary: null, ...extra },
    versions,
  }
}

export const TWO_VERSIONS: SiteDocument['versions'] = [
  { number: 2, effectiveOn: '2026-10-20', supersededOn: null },
  { number: 1, effectiveOn: '2026-10-04', supersededOn: '2026-10-20' },
]

export const CONSENT_ITEMS = [
  { label: '목적', text: '로그인 유지, 내 지역 도서관 찾기' },
  { label: '항목', text: '카카오 회원 식별 번호, 거주·직장 지역, 직접 추가한 도서관' },
  { label: '보유 기간', text: '탈퇴 또는 마지막 로그인 뒤 1년' },
  { label: '거부할 권리', text: '동의하지 않으면 가입할 수 없지만, 검색은 그대로 쓸 수 있어요' },
]

export function noticeList(extra: Partial<NoticeList> = {}): NoticeList {
  return { pinned: [], items: [], page: 1, pageSize: 10, totalCount: 0, hasMore: false, ...extra }
}

export function noticeOf(extra: Partial<Notice> = {}): Notice {
  return {
    id: 7,
    title: '점검 안내',
    postedOn: '2026-11-03',
    editedOn: null,
    version: { number: 1, effectiveOn: '2026-11-03', current: true, body: '점검이 있어요.' },
    versions: [{ number: 1, effectiveOn: '2026-11-03', supersededOn: null }],
    ...extra,
  }
}

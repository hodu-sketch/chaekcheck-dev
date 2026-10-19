import { ApiError } from '../../api/client'

/**
 * Why a search was refused (cycle 08 R2, flow C): `too_many` = 429 from nginx (too many searches from one address),
 * `busy` = 503 `server_busy` (every search slot is taken). The results already received stay; the user searches
 * again by hand, so there is no retry button [확정].
 */
export type Blocked = 'too_many' | 'busy'

/** [확정] (cycle 08 handoff step 7-2). */
export const BLOCKED_TEXT: Record<Blocked, string> = {
  too_many: '검색을 너무 자주 했어요. 1분쯤 뒤에 다시 해 주세요.',
  busy: '지금 찾는 사람이 많아요. 잠시 뒤에 다시 해 주세요.',
}

export function blockedBy(error: unknown): Blocked | null {
  if (!(error instanceof ApiError)) return null
  if (error.status === 429) return 'too_many'
  if (error.status === 503) return 'busy'
  return null
}


import { ApiError } from '../../api/client'

/** 20 libraries already (409 `library_limit_reached` from the server, or the browser's own limit). */
export class LibraryLimitError extends Error {}

/** The browser would not store the change (blocked or full storage); nothing changed (cycle 07, ADR-0057). */
export class BrowserStorageError extends Error {}

/** Wording of a failed add or remove [안 문구, 규칙 확정] (cycle 07 flow S10), except the marked dev placeholder. */
export const LIBRARY_CHANGE_TEXT = {
  limit: '직접 추가는 20곳까지예요. 하나를 빼면 추가할 수 있어요',
  saveFailed: '저장하지 못했어요. 잠시 뒤 다시 해 주세요',
  // dev placeholder (not in flow): the library was switched off or removed meanwhile
  notFound: '지금은 추가할 수 없는 도서관이에요. 목록을 다시 확인해 주세요',
  browserBlocked: '이 브라우저에 저장하지 못했어요',
} as const

/** What a failed `addLibrary` / `removeLibrary` says (the my page and 상세검색 alike). */
export function libraryChangeMessage(error: unknown): string {
  if (error instanceof LibraryLimitError) return LIBRARY_CHANGE_TEXT.limit
  if (error instanceof BrowserStorageError) return LIBRARY_CHANGE_TEXT.browserBlocked
  if (error instanceof ApiError && error.problem.code === 'library_limit_reached') return LIBRARY_CHANGE_TEXT.limit
  if (error instanceof ApiError && error.problem.code === 'library_not_found') return LIBRARY_CHANGE_TEXT.notFound
  // 401 after the refresh: the browser is signed out and the screen shows the browser's list
  return LIBRARY_CHANGE_TEXT.saveFailed
}

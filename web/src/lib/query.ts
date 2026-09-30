export const QUERY_MIN_LENGTH = 2
export const QUERY_MAX_LENGTH = 50

/** Same normalization as the server: trim, collapse whitespace runs to one space. */
export function normalizeQuery(raw: string): string {
  return raw.trim().replace(/\s+/g, ' ')
}

/** Length in characters (code points), like the server. */
export function queryLength(text: string): number {
  return [...text].length
}

export type QueryProblem = 'too_short' | 'too_long' | null

export function checkQuery(normalized: string): QueryProblem {
  const length = queryLength(normalized)
  if (length < QUERY_MIN_LENGTH) return 'too_short'
  if (length > QUERY_MAX_LENGTH) return 'too_long'
  return null
}

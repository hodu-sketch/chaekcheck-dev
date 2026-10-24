import { fetchCsrfToken, readCsrfCookie } from './auth'
import { readProblem } from './client'
import { SseParser } from './sseParser'
import type { LibraryScope, VisitFilter } from './types'

/**
 * The AI book search (cycle 24, ADR-0150~0153): `GET /api/v1/ai-searches/status` (200 on, 404 off) and
 * `POST /api/v1/ai-searches/stream`. The question goes in the body only, never in the address (ADR-0152). Its own
 * POST reader on the shared `SseParser` (the GET reader of `searchStream.ts` stays as it is).
 */

export type AiTurn = { question: string; bookIds: number[] }

export interface AiSearchBody {
  question: string
  /** The question's number in this conversation, 1 to 20 (counted here, R11-10). */
  turnNo: number
  /** At most 5 earlier turns, oldest first. */
  history: AiTurn[]
  residenceRegionIds: number[]
  workRegionIds: number[]
  libraryIds: number[]
  scope: LibraryScope
  visit: VisitFilter
}

export type AiCard = {
  no: number
  bookId: number
  title: string
  author: string | null
  coverUrl: string | null
  myLibraryCount: number
}

export type AiDoneResult = 'ok' | 'no_match' | 'no_text' | 'partial'

export type AiStreamEvent =
  | { name: 'title-guess'; data: { title: string } }
  | { name: 'head-delta'; data: { text: string } }
  | { name: 'card'; data: AiCard }
  | { name: 'card-delta'; data: { bookId: number; text: string } }
  | { name: 'done'; data: { result: AiDoneResult; bookIds: number[] } }
  | { name: 'error'; data: { code: string } }

const EVENT_NAMES = new Set(['title-guess', 'head-delta', 'card', 'card-delta', 'done', 'error'])

const STREAM_URL = '/api/v1/ai-searches/stream'
const CSRF_HEADER = 'X-XSRF-TOKEN'

/** Whether the AI search is on (status 200) or off (404); other failures count as unknown and keep the AI tab. */
export async function fetchAiStatus(): Promise<'on' | 'off'> {
  const response = await fetch('/api/v1/ai-searches/status', { headers: { Accept: 'application/json' } })
  if (response.status === 404) return 'off'
  if (!response.ok) throw await readProblem(response)
  return 'on'
}

/**
 * Sends one question and hands on the events in order until `done` or `error`. Throws `ApiError` for a refusal
 * before the stream (400, 404, 429 with `retryAfter`, 503) and an ordinary error when the connection breaks.
 */
export async function aiSearchStream(
  body: AiSearchBody,
  onEvent: (event: AiStreamEvent) => void,
  signal: AbortSignal,
): Promise<void> {
  const text = JSON.stringify(body)
  let response = await send(text, readCsrfCookie(), signal)
  if (await isCsrfRejection(response)) {
    await fetchCsrfToken()
    response = await send(text, readCsrfCookie(), signal)
  }
  if (!response.ok) throw await readProblem(response)
  if (!response.body) throw new Error('Stream has no body')

  const reader = response.body.pipeThrough(new TextDecoderStream()).getReader()
  const parser = new SseParser()
  for (;;) {
    const { value, done } = await reader.read()
    if (done) throw new Error('The stream ended before its last event')
    for (const raw of parser.push(value)) {
      if (!EVENT_NAMES.has(raw.event)) continue
      const event = { name: raw.event, data: JSON.parse(raw.data) } as AiStreamEvent
      onEvent(event)
      if (event.name === 'done' || event.name === 'error') {
        await reader.cancel()
        return
      }
    }
  }
}

function send(body: string, token: string | null, signal: AbortSignal): Promise<Response> {
  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
    Accept: 'text/event-stream, application/problem+json',
  }
  if (token !== null) headers[CSRF_HEADER] = token
  return fetch(STREAM_URL, { method: 'POST', credentials: 'same-origin', headers, body, signal })
}

async function isCsrfRejection(response: Response): Promise<boolean> {
  if (response.status !== 403) return false
  try {
    const problem = (await response.clone().json()) as { code?: string }
    return problem.code === 'csrf_invalid'
  } catch {
    return false
  }
}

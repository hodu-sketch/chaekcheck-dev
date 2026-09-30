import { afterEach, describe, expect, it, vi } from 'vitest'
import { fetchLibraryPage } from './client'
import { batchStream, searchStream } from './searchStream'

function requestedUrl(fetchMock: ReturnType<typeof vi.fn>): URL {
  return new URL(fetchMock.mock.calls[0][0] as string, 'http://localhost')
}

/** A 400 answer ends each call right after the request, so only the URL matters. */
function mockFetch() {
  const fetchMock = vi.fn().mockResolvedValue(
    new Response(JSON.stringify({ title: 'Bad Request', status: 400, code: 'validation_error' }), { status: 400 }),
  )
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('requests carry the condition as its own parameter, never q (ADR-0025)', () => {
  it('first batch', async () => {
    const fetchMock = mockFetch()
    await searchStream(
      { field: 'author', query: '김달', filter: 'all', scope: 'joinable', visit: 'none', residenceRegionIds: [1116], workRegionIds: [] },
      () => {},
      new AbortController().signal,
    ).catch(() => {})

    const url = requestedUrl(fetchMock)
    expect(url.pathname).toBe('/api/v1/ebook-searches/stream')
    expect(url.searchParams.get('author')).toBe('김달')
    expect(url.searchParams.has('q')).toBe(false)
    expect(url.searchParams.has('title')).toBe(false)
    expect(url.searchParams.get('scope')).toBe('joinable')
    expect(url.searchParams.get('visit')).toBe('none')
    expect(url.searchParams.get('residenceRegionIds')).toBe('1116')
  })

  it('next batch', async () => {
    const fetchMock = mockFetch()
    await batchStream(
      { field: 'publisher', query: '빅피시', filter: 'available', batchNo: 2, candidates: [{ libraryId: 3, page: 2 }] },
      () => {},
      new AbortController().signal,
    ).catch(() => {})

    const url = requestedUrl(fetchMock)
    expect(url.searchParams.get('publisher')).toBe('빅피시')
    expect(url.searchParams.has('q')).toBe(false)
    expect(url.searchParams.get('candidates')).toBe('3:2')
  })

  it('a library "more" page', async () => {
    const fetchMock = mockFetch()
    await fetchLibraryPage(12, 'author', '김달', 2).catch(() => {})

    const url = requestedUrl(fetchMock)
    expect(url.pathname).toBe('/api/v1/availability/libraries/12/ebooks')
    expect(url.search).toBe('?author=%EA%B9%80%EB%8B%AC&page=2')
  })
})

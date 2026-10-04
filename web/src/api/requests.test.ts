import { afterEach, describe, expect, it, vi } from 'vitest'
import { bookSearchParams, fetchBook, fetchLibraries, fetchLibraryPage } from './client'
import { batchStream, holdingStream, searchStream } from './searchStream'

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
      { field: 'author', query: '김달', filter: 'all', scope: 'joinable', visit: 'none', residenceRegionIds: [1116], workRegionIds: [], libraryIds: [] },
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

describe('libraryIds (cycle 06): sent only when not empty', () => {
  const plan = { scope: 'joinable' as const, visit: 'any' as const, residenceRegionIds: [], workRegionIds: [] }

  it('first batch and holding stream', async () => {
    const fetchMock = mockFetch()
    const base = { field: 'title' as const, query: '사랑', filter: 'all' as const, ...plan }
    await searchStream({ ...base, libraryIds: [12, 207] }, () => {}, new AbortController().signal).catch(() => {})
    await searchStream({ ...base, libraryIds: [] }, () => {}, new AbortController().signal).catch(() => {})
    await holdingStream({ bookIds: [1], filter: 'all', ...plan, libraryIds: [12] }, () => {}, new AbortController().signal).catch(
      () => {},
    )

    const urls = fetchMock.mock.calls.map(([url]) => new URL(url as string, 'http://localhost'))
    expect(urls[0].searchParams.get('libraryIds')).toBe('12,207')
    expect(urls[1].searchParams.has('libraryIds')).toBe(false)
    expect(urls[2].searchParams.get('libraryIds')).toBe('12')
  })

  it('book search and one book', async () => {
    const query = {
      title: '사랑', author: null, publisher: null, exact: [], roles: [], sort: 'default' as const, page: 1, ...plan,
    }
    expect(bookSearchParams({ ...query, libraryIds: [12] }).get('libraryIds')).toBe('12')
    expect(bookSearchParams({ ...query, libraryIds: [] }).has('libraryIds')).toBe(false)

    const fetchMock = mockFetch()
    await fetchBook('3', { ...plan, libraryIds: [12, 207] }).catch(() => {})
    expect(requestedUrl(fetchMock).searchParams.get('libraryIds')).toBe('12,207')
  })
})

describe('GET /api/v1/libraries', () => {
  it('sends only the filled region fields and returns the rows', async () => {
    const fetchMock = vi.fn(
      async (_url: string) =>
        new Response(JSON.stringify({ libraries: [{ id: 2, name: '고흥', matchedBy: [] }] }), { status: 200 }),
    )
    vi.stubGlobal('fetch', fetchMock)

    const rows = await fetchLibraries([], [1102])

    expect(requestedUrl(fetchMock).search).toBe('?workRegionIds=1102')
    expect(rows).toEqual([{ id: 2, name: '고흥', matchedBy: [] }])
    await fetchLibraries([], [])
    expect(new URL(fetchMock.mock.calls[1][0] as string, 'http://localhost').search).toBe('')
  })
})


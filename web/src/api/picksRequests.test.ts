import { afterEach, describe, expect, it, vi } from 'vitest'
import { fetchPicks } from './client'
import { holdingStream } from './searchStream'

function mockFetch() {
  const fetchMock = vi.fn().mockResolvedValue(
    new Response(JSON.stringify({ title: 'Bad Request', status: 400, code: 'validation_error' }), { status: 400 }),
  )
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

const requestedUrl = (fetchMock: ReturnType<typeof vi.fn>) => new URL(fetchMock.mock.calls[0][0] as string, 'http://localhost')

afterEach(() => {
  vi.unstubAllGlobals()
})

const plan = { scope: 'all' as const, visit: 'any' as const, residenceRegionIds: [], workRegionIds: [], libraryIds: [] }

/** Cycle 14 (design-delta API); the holding limit from cycle 10. */
describe('picks requests', () => {
  it('sends the plan like the book search', async () => {
    const fetchMock = mockFetch()
    await fetchPicks('librarian', { ...plan, scope: 'joinable', visit: 'none', workRegionIds: [3], libraryIds: [7, 8] }).catch(
      () => {},
    )
    const url = requestedUrl(fetchMock)
    expect(url.pathname).toBe('/api/v1/picks/librarian')
    expect(Object.fromEntries(url.searchParams)).toEqual({
      scope: 'joinable',
      visit: 'none',
      workRegionIds: '3',
      libraryIds: '7,8',
    })
  })

  it('sends no query without a plan', async () => {
    const fetchMock = mockFetch()
    await fetchPicks('snu100', plan).catch(() => {})
    expect(fetchMock.mock.calls[0][0]).toBe('/api/v1/picks/snu100')
  })

  it('asks only the first three holdings for S14 (holdingLimit, AC-8)', async () => {
    const fetchMock = mockFetch()
    await holdingStream({ bookIds: [1], filter: 'all', ...plan, holdingLimit: 3 }, () => {}, new AbortController().signal).catch(
      () => {},
    )
    expect(requestedUrl(fetchMock).searchParams.get('holdingLimit')).toBe('3')
  })

  it('leaves holdingLimit out otherwise', async () => {
    const fetchMock = mockFetch()
    await holdingStream({ bookIds: [1], filter: 'all', ...plan }, () => {}, new AbortController().signal).catch(() => {})
    expect(requestedUrl(fetchMock).searchParams.has('holdingLimit')).toBe(false)
  })
})

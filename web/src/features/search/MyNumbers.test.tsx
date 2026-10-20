import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { LibraryEligibilityRow } from '../../api/types'
import { basisWord, countRows, MyNumbers } from './MyNumbers'

const TREE = [{ id: 11, name: '서울특별시', shortName: '서울', children: [{ id: 1116, name: '강서구' }] }]

const row = (id: number, eligibility: LibraryEligibilityRow['eligibility'], visitRequired = false) =>
  ({ id, name: `도서관 ${id}`, eligibility, matchedBy: [], visitRequired }) as unknown as LibraryEligibilityRow

function serve(libraries: () => Response) {
  const calls: string[] = []
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string) => {
      calls.push(url)
      if (url === '/api/v1/regions') return new Response(JSON.stringify(TREE), { status: 200 })
      if (url.startsWith('/api/v1/libraries')) return libraries()
      throw new Error(`unexpected ${url}`)
    }),
  )
  return calls
}

function renderNumbers(client = new QueryClient({ defaultOptions: { queries: { retry: false } } })) {
  return render(
    <QueryClientProvider client={client}>
      <MyNumbers residence={[1116]} work={[]} />
    </QueryClientProvider>,
  )
}

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

describe('the numbers after a pick (handoff 5-A-4)', () => {
  it('counts by region, 어디 살든 and the visits, with two names and 이름 보기', async () => {
    const rows = [row(1, 'ELIGIBLE'), row(2, 'ELIGIBLE', true), row(3, 'OPEN'), row(4, 'OPEN', true), row(5, 'OTHER' as never)]
    const calls = serve(() => new Response(JSON.stringify({ libraries: rows }), { status: 200 }))
    renderNumbers()
    expect(await screen.findByText('서울 강서구 → 가입할 수 있는 도서관 4곳')).toBeTruthy()
    expect(screen.getByText('서울 거주로 2')).toBeTruthy()
    expect(screen.getByText('어디 살든 2')).toBeTruthy()
    expect(screen.getByText('그중 가야 하는 곳 2')).toBeTruthy()
    // one request for these regions
    expect(calls.filter((url) => url.startsWith('/api/v1/libraries'))).toEqual(['/api/v1/libraries?residenceRegionIds=1116'])
    await userEvent.setup().click(screen.getByRole('button', { name: '이름 보기 ▾' }))
    expect(screen.getAllByRole('listitem').map((item) => item.textContent)).toEqual([
      '도서관 1',
      '도서관 2',
      '도서관 3',
      '도서관 4',
    ])
    // 접기 goes back to the two names (user 2026-10-20: "줄이기도 좀 있어줘")
    await userEvent.setup().click(screen.getByRole('button', { name: '접기 ▴' }))
    expect(screen.queryAllByRole('listitem')).toHaveLength(0)
    expect(screen.getByRole('button', { name: '이름 보기 ▾' })).toBeTruthy()
  })

  it('asks again within 5 minutes only from the cache: no second request', async () => {
    const calls = serve(() => new Response(JSON.stringify({ libraries: [row(1, 'ELIGIBLE')] }), { status: 200 }))
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const first = renderNumbers(client)
    await screen.findByText('서울 거주로 1')
    first.unmount()
    renderNumbers(client)
    await screen.findByText('서울 거주로 1')
    expect(calls.filter((url) => url.startsWith('/api/v1/libraries'))).toHaveLength(1)
  })

  it('shows nothing when the list cannot be read', async () => {
    const calls = serve(() => new Response('{}', { status: 500 }))
    const { container } = renderNumbers()
    await waitFor(() => expect(calls.some((url) => url.startsWith('/api/v1/libraries'))).toBe(true))
    await new Promise((resolve) => setTimeout(resolve, 50))
    expect(container.textContent).toBe('')
  })

  it('names the side: 거주 / 재직 / 거주·재직, and counts only joinable rows', () => {
    expect(basisWord([1], [])).toBe('거주')
    expect(basisWord([], [2])).toBe('재직')
    expect(basisWord([1], [2])).toBe('거주·재직')
    const counted = countRows([row(1, 'ELIGIBLE', true), row(2, 'UNKNOWN' as never, true)])
    expect([counted.eligible.length, counted.open.length, counted.visit]).toEqual([1, 0, 1])
  })
})

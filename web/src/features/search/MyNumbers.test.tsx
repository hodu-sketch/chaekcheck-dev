import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { LibraryEligibilityRow } from '../../api/types'
import { countRows, countSide, MyNumbers, NUMBERS_TEXT } from './MyNumbers'

const TREE = [
  { id: 11, name: '서울특별시', shortName: '서울', children: [{ id: 1116, name: '강서구' }] },
  { id: 23, name: '인천광역시', shortName: '인천', children: [{ id: 2310, name: '강화군' }] },
]

const row = (
  id: number,
  eligibility: LibraryEligibilityRow['eligibility'],
  visitRequired = false,
  matchedBy: string[] = eligibility === 'ELIGIBLE' ? ['RESIDENCE'] : [],
) => ({ id, name: `도서관 ${id}`, eligibility, matchedBy, visitRequired }) as unknown as LibraryEligibilityRow

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

function renderNumbers(
  client = new QueryClient({ defaultOptions: { queries: { retry: false } } }),
  work: number[] = [],
) {
  return render(
    <QueryClientProvider client={client}>
      <MyNumbers residence={[1116]} work={work} />
    </QueryClientProvider>,
  )
}

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

describe('the numbers after a pick (handoff 5-A-4)', () => {
  it('counts by region and 어디 살든, no 방문 가입 pill, with two names and 이름 보기', async () => {
    const rows = [row(1, 'ELIGIBLE'), row(2, 'ELIGIBLE', true), row(3, 'OPEN'), row(4, 'OPEN', true), row(5, 'OTHER' as never)]
    const calls = serve(() => new Response(JSON.stringify({ libraries: rows }), { status: 200 }))
    renderNumbers()
    expect(await screen.findByText('서울 강서구 → 빌릴 수 있는 도서관 4곳')).toBeTruthy()
    expect(screen.getByText('서울 거주로 2')).toBeTruthy()
    expect(screen.getByText('어디 살든 2')).toBeTruthy()
    expect(screen.queryByText(/가야 하는 곳/)).toBeNull()
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

  it('one pill a side with its 시도, a side without libraries left out (user 2026-10-20)', async () => {
    const rows = [
      row(1, 'ELIGIBLE', false, ['RESIDENCE']),
      row(2, 'ELIGIBLE', false, ['RESIDENCE', 'WORK']),
      row(3, 'ELIGIBLE', false, ['WORK']),
      row(4, 'OPEN'),
    ]
    serve(() => new Response(JSON.stringify({ libraries: rows }), { status: 200 }))
    renderNumbers(undefined, [2310])
    expect(await screen.findByText('서울 거주로 2')).toBeTruthy()
    expect(screen.getByText('인천 재직으로 2')).toBeTruthy()
    cleanup()

    serve(() => new Response(JSON.stringify({ libraries: [row(1, 'ELIGIBLE', false, ['RESIDENCE']), row(4, 'OPEN')] }), { status: 200 }))
    renderNumbers(undefined, [2310])
    expect(await screen.findByText('서울 거주로 1')).toBeTruthy()
    expect(screen.queryByText(/재직으로/)).toBeNull()
  })

  it('counts a side and only joinable rows', () => {
    expect(countSide([row(1, 'ELIGIBLE', false, ['WORK']), row(2, 'OPEN')], 'WORK')).toBe(1)
    // 로 / 으로 by the last letter (user 2026-10-20: "서울 재직로 말고 서울 재직으로")
    expect(NUMBERS_TEXT.byRegion('서울', '거주', 3)).toBe('서울 거주로 3')
    expect(NUMBERS_TEXT.byRegion('서울', '재직', 3)).toBe('서울 재직으로 3')
    const counted = countRows([row(1, 'ELIGIBLE', true), row(2, 'UNKNOWN' as never, true)])
    expect([counted.eligible.length, counted.open.length]).toEqual([1, 0])
  })
})

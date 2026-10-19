import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { RegionGrid } from './RegionGrid'
import { MAX_REGIONS_PER_FIELD } from './regionStorage'

/** The region picker (cycle 22 R12; chips + [+] with two selects on every width, user 2026-10-19): chips, the 20 limit, loading and failure. */

const SEOUL = {
  id: 11,
  name: '서울특별시',
  shortName: '서울',
  children: [
    { id: 1114, name: '마포구' },
    { id: 1102, name: '중구' },
  ],
}
const SEJONG = { id: 36, name: '세종특별자치시', shortName: '세종', children: [{ id: 3611, name: '세종시' }] }
const TREE = [SEOUL, SEJONG]

function stubRegions(respond: () => Promise<Response> | Response) {
  const fetchMock = vi.fn(async (url: string) => {
    if (url === '/api/v1/regions') return respond()
    throw new Error(`unexpected ${url}`)
  })
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

/** A caller that keeps the value, as RegionContext will. */
function Harness({ initial = [], full, onToggle }: { initial?: number[]; full?: boolean; onToggle?: (id: number) => void }) {
  const [value, setValue] = useState<number[]>(initial)
  return (
    <RegionGrid
      field="residence"
      value={value}
      full={full ?? value.length >= MAX_REGIONS_PER_FIELD}
      onToggle={(id) => {
        onToggle?.(id)
        setValue((v) => (v.includes(id) ? v.filter((x) => x !== id) : [...v, id]))
      }}
    />
  )
}

function renderGrid(props: Parameters<typeof Harness>[0] = {}) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={client}>
      <Harness {...props} />
    </QueryClientProvider>,
  )
}

describe('RegionGrid notices', () => {
  it('shows the resolution notes under the chips', async () => {
    stubRegions(() => new Response(JSON.stringify(TREE), { status: 200 }))
    render(
      <QueryClientProvider client={new QueryClient()}>
        <RegionGrid
          field="work"
          value={[1102]}
          full={false}
          onToggle={() => {}}
          notices={[{ kind: 'replaced', message: '행정구역 변경으로 시험 폐지구가 서울 중구로 바뀌었습니다' }]}
        />
      </QueryClientProvider>,
    )

    expect((await screen.findByRole('status')).textContent).toContain('서울 중구로 바뀌었습니다')
    expect(screen.getByRole('list', { name: '고른 일하는 곳' })).toBeTruthy()
  })
})

describe('RegionGrid', () => {
  function optionTexts(select: HTMLElement) {
    return within(select)
      .getAllByRole('option')
      .map((o) => o.textContent)
  }

  /** Waits for the list, then presses the field's +. */
  async function openSelects(user: ReturnType<typeof userEvent.setup>) {
    const add = await screen.findByRole('button', { name: '사는 곳 추가' })
    await waitFor(() => expect((add as HTMLButtonElement).disabled).toBe(false))
    await user.click(add)
    return add
  }

  it('at rest only the chips and + show; + opens the two selects', async () => {
    stubRegions(() => new Response(JSON.stringify(TREE), { status: 200 }))
    renderGrid()
    const user = userEvent.setup()

    // no chips yet: the row is just +
    const add = await screen.findByRole('button', { name: '사는 곳 추가' })
    expect(screen.queryByRole('list', { name: '고른 사는 곳' })).toBeNull()
    await waitFor(() => expect((add as HTMLButtonElement).disabled).toBe(false))
    expect(screen.queryByRole('combobox')).toBeNull()
    expect(screen.queryByRole('checkbox')).toBeNull()
    expect(screen.queryByRole('button', { name: '서울' })).toBeNull()

    await user.click(add)
    expect(add.getAttribute('aria-expanded')).toBe('true')
    const sido = screen.getByRole('combobox', { name: '사는 곳 시도' }) as HTMLSelectElement
    expect(optionTexts(sido)).toEqual(['서울', '세종'])
    expect(sido.value).toBe('11')
    expect(optionTexts(screen.getByRole('combobox', { name: '사는 곳 시군구' }))).toEqual(['시군구 고르기', '마포구', '중구'])
  })

  it('pressing it again (×) closes the selects without adding', async () => {
    stubRegions(() => new Response(JSON.stringify(TREE), { status: 200 }))
    const onToggle = vi.fn()
    renderGrid({ onToggle })
    const user = userEvent.setup()

    const add = await openSelects(user)
    await user.click(add)

    expect(add.getAttribute('aria-expanded')).toBe('false')
    expect(screen.queryByRole('combobox')).toBeNull()
    expect(onToggle).not.toHaveBeenCalled()
  })

  it('the 시도 select starts on the 시도 of the first chosen region; chosen ones show ✓ and are disabled', async () => {
    stubRegions(() => new Response(JSON.stringify(TREE), { status: 200 }))
    renderGrid({ initial: [3611] })
    await openSelects(userEvent.setup())

    expect((screen.getByRole('combobox', { name: '사는 곳 시도' }) as HTMLSelectElement).value).toBe('36')
    expect(optionTexts(screen.getByRole('combobox', { name: '사는 곳 시군구' }))).toEqual(['시군구 고르기', '✓ 세종시'])
    expect((screen.getByRole('option', { name: '✓ 세종시' }) as HTMLOptionElement).disabled).toBe(true)
  })

  it('picking a 시도 lists its 시군구; Sejong picks none by itself', async () => {
    stubRegions(() => new Response(JSON.stringify(TREE), { status: 200 }))
    const onToggle = vi.fn()
    renderGrid({ onToggle })
    const user = userEvent.setup()
    await openSelects(user)

    await user.selectOptions(screen.getByRole('combobox', { name: '사는 곳 시도' }), '세종')

    const sigungu = screen.getByRole('combobox', { name: '사는 곳 시군구' }) as HTMLSelectElement
    expect(optionTexts(sigungu)).toEqual(['시군구 고르기', '세종시'])
    expect(sigungu.value).toBe('')
    expect(onToggle).not.toHaveBeenCalled()
  })

  it('picking a 시군구 adds it at once, the chip shows and the selects hide again', async () => {
    stubRegions(() => new Response(JSON.stringify(TREE), { status: 200 }))
    const onToggle = vi.fn()
    renderGrid({ onToggle })
    const user = userEvent.setup()
    const add = await openSelects(user)

    await user.selectOptions(screen.getByRole('combobox', { name: '사는 곳 시군구' }), '마포구')

    expect(onToggle).toHaveBeenCalledExactlyOnceWith(1114)
    expect(screen.queryByRole('combobox')).toBeNull()
    expect(add.getAttribute('aria-expanded')).toBe('false')
    expect(within(screen.getByRole('list', { name: '고른 사는 곳' })).getByText('서울 마포구')).toBeTruthy()

    // opened again: the added one has ✓ and cannot be picked
    await user.click(add)
    expect(optionTexts(screen.getByRole('combobox', { name: '사는 곳 시군구' }))).toEqual(['시군구 고르기', '✓ 마포구', '중구'])
    expect((screen.getByRole('option', { name: '✓ 마포구' }) as HTMLOptionElement).disabled).toBe(true)
  })

  it('the chip × removes a region', async () => {
    stubRegions(() => new Response(JSON.stringify(TREE), { status: 200 }))
    const onToggle = vi.fn()
    renderGrid({ initial: [1114], onToggle })

    await userEvent.setup().click(await screen.findByRole('button', { name: '서울 마포구 빼기' }))
    expect(onToggle).toHaveBeenCalledWith(1114)
    expect(screen.queryByRole('list', { name: '고른 사는 곳' })).toBeNull()
  })

  it('at the limit + is disabled and the limit line shows', async () => {
    stubRegions(() => new Response(JSON.stringify(TREE), { status: 200 }))
    renderGrid({ initial: [1114], full: true })

    await screen.findByText('서울 마포구')
    expect((screen.getByRole('button', { name: '사는 곳 추가' }) as HTMLButtonElement).disabled).toBe(true)
    expect(screen.getByText('최대 20곳이에요')).toBeTruthy()
  })

  it('no limit line below the limit', async () => {
    stubRegions(() => new Response(JSON.stringify(TREE), { status: 200 }))
    renderGrid({ initial: [1114] })

    await screen.findByText('서울 마포구')
    expect(screen.queryByText('최대 20곳이에요')).toBeNull()
  })

  it('shows the loading line, then the failure line; 다시 시도 reads the list again', async () => {
    let fail = true
    const fetchMock = stubRegions(() =>
      fail ? new Response('{}', { status: 500 }) : new Response(JSON.stringify(TREE), { status: 200 }),
    )
    renderGrid()

    expect(screen.getByText('불러오는 중…')).toBeTruthy()
    const alert = await screen.findByRole('alert')
    expect(alert.textContent).toBe('지역 목록을 불러오지 못했어요 다시 시도')
    expect((screen.getByRole('button', { name: '사는 곳 추가' }) as HTMLButtonElement).disabled).toBe(true)
    fail = false
    const user = userEvent.setup()
    await user.click(within(alert).getByRole('button', { name: '다시 시도' }))
    await openSelects(user)
    expect(screen.getByRole('combobox', { name: '사는 곳 시도' })).toBeTruthy()
    expect(fetchMock).toHaveBeenCalledTimes(2)
  })
})

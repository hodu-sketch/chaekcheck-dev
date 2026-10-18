import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { RegionGrid } from './RegionGrid'
import { MAX_REGIONS_PER_FIELD } from './regionStorage'

/** The region grid without the dialog (cycle 22 R12): cells, chips, the 20 limit, loading and failure. */

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

describe('RegionGrid', () => {
  it('shows the loading line while the list is read', async () => {
    stubRegions(() => new Promise<Response>(() => {}))
    renderGrid()
    expect(screen.getByText('불러오는 중…')).toBeTruthy()
  })

  it('checking a cell calls onToggle and adds the chip; checking again removes it', async () => {
    stubRegions(() => new Response(JSON.stringify(TREE), { status: 200 }))
    const onToggle = vi.fn()
    renderGrid({ onToggle })
    const user = userEvent.setup()

    const cell = (await screen.findByRole('checkbox', { name: '마포구' })) as HTMLInputElement
    expect(cell.checked).toBe(false)
    await user.click(cell)

    expect(onToggle).toHaveBeenCalledWith(1114)
    expect(cell.checked).toBe(true)
    const chips = screen.getByRole('list', { name: '고른 사는 곳' })
    expect(within(chips).getByText('서울 마포구')).toBeTruthy()
    // the 시도 shows how many are chosen in it
    expect(screen.getByRole('button', { name: '서울1' }).getAttribute('aria-pressed')).toBe('true')

    await user.click(cell)
    expect(onToggle).toHaveBeenLastCalledWith(1114)
    expect(cell.checked).toBe(false)
    expect(screen.queryByRole('list', { name: '고른 사는 곳' })).toBeNull()
  })

  it('the chip × removes the region through onToggle', async () => {
    stubRegions(() => new Response(JSON.stringify(TREE), { status: 200 }))
    const onToggle = vi.fn()
    renderGrid({ initial: [1102], onToggle })

    await userEvent.setup().click(await screen.findByRole('button', { name: '서울 중구 빼기' }))

    expect(onToggle).toHaveBeenCalledWith(1102)
    expect((screen.getByRole('checkbox', { name: '중구' }) as HTMLInputElement).checked).toBe(false)
  })

  it('Sejong: choosing the 시도 picks no 시군구 by itself', async () => {
    stubRegions(() => new Response(JSON.stringify(TREE), { status: 200 }))
    const onToggle = vi.fn()
    renderGrid({ onToggle })
    const user = userEvent.setup()

    await user.click(await screen.findByRole('button', { name: '세종' }))

    expect(screen.getByRole('button', { name: '세종' }).getAttribute('aria-pressed')).toBe('true')
    const cell = screen.getByRole('checkbox', { name: '세종시' }) as HTMLInputElement
    expect(cell.checked).toBe(false)
    expect(onToggle).not.toHaveBeenCalled()
    await user.click(cell)
    expect(onToggle).toHaveBeenCalledWith(3611)
  })

  it('at the limit the unchecked cells are disabled and the limit line shows', async () => {
    stubRegions(() => new Response(JSON.stringify(TREE), { status: 200 }))
    renderGrid({ initial: [1114], full: true })

    const unchecked = (await screen.findByRole('checkbox', { name: '중구' })) as HTMLInputElement
    expect(unchecked.disabled).toBe(true)
    // a chosen one can still be taken off
    expect((screen.getByRole('checkbox', { name: '마포구' }) as HTMLInputElement).disabled).toBe(false)
    expect(screen.getByText('최대 20곳이에요')).toBeTruthy()
  })

  it('no limit line below the limit', async () => {
    stubRegions(() => new Response(JSON.stringify(TREE), { status: 200 }))
    renderGrid({ initial: [1114] })

    expect(((await screen.findByRole('checkbox', { name: '중구' })) as HTMLInputElement).disabled).toBe(false)
    expect(screen.queryByText('최대 20곳이에요')).toBeNull()
  })

  it('a failed list shows the line and 다시 시도 reads it again', async () => {
    let fail = true
    const fetchMock = stubRegions(() =>
      fail ? new Response('{}', { status: 500 }) : new Response(JSON.stringify(TREE), { status: 200 }),
    )
    renderGrid()

    const alert = await screen.findByRole('alert')
    expect(alert.textContent).toBe('지역 목록을 불러오지 못했어요 다시 시도')
    fail = false
    await userEvent.setup().click(within(alert).getByRole('button', { name: '다시 시도' }))

    expect(await screen.findByRole('checkbox', { name: '마포구' })).toBeTruthy()
    expect(fetchMock).toHaveBeenCalledTimes(2)
  })
})

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

describe('RegionGrid on phones', () => {
  function phone() {
    vi.stubGlobal('matchMedia', (query: string) => ({
      matches: query === '(width <= 640px)',
      media: query,
      addEventListener: () => {},
      removeEventListener: () => {},
    }))
  }

  function optionTexts(select: HTMLElement) {
    return within(select)
      .getAllByRole('option')
      .map((o) => o.textContent)
  }

  it('shows two select boxes instead of the grid', async () => {
    phone()
    stubRegions(() => new Response(JSON.stringify(TREE), { status: 200 }))
    renderGrid()

    const sido = (await screen.findByRole('combobox', { name: '사는 곳 시도' })) as HTMLSelectElement
    expect(optionTexts(sido)).toEqual(['서울', '세종'])
    expect(sido.value).toBe('11')
    expect(optionTexts(screen.getByRole('combobox', { name: '사는 곳 시군구' }))).toEqual(['시군구 고르기', '마포구', '중구'])
    expect(screen.queryByRole('checkbox')).toBeNull()
    expect(screen.queryByRole('button', { name: '서울' })).toBeNull()
  })

  it('the 시도 select starts on the 시도 of the first chosen region', async () => {
    phone()
    stubRegions(() => new Response(JSON.stringify(TREE), { status: 200 }))
    renderGrid({ initial: [3611] })

    expect(((await screen.findByRole('combobox', { name: '사는 곳 시도' })) as HTMLSelectElement).value).toBe('36')
    expect(optionTexts(screen.getByRole('combobox', { name: '사는 곳 시군구' }))).toEqual(['시군구 고르기', '✓ 세종시'])
  })

  it('picking a 시도 lists its 시군구; Sejong picks none by itself', async () => {
    phone()
    stubRegions(() => new Response(JSON.stringify(TREE), { status: 200 }))
    const onToggle = vi.fn()
    renderGrid({ onToggle })
    const user = userEvent.setup()

    await user.selectOptions(await screen.findByRole('combobox', { name: '사는 곳 시도' }), '세종')

    const sigungu = screen.getByRole('combobox', { name: '사는 곳 시군구' }) as HTMLSelectElement
    expect(optionTexts(sigungu)).toEqual(['시군구 고르기', '세종시'])
    expect(sigungu.value).toBe('')
    expect(onToggle).not.toHaveBeenCalled()
  })

  it('picking a 시군구 calls onToggle, adds the chip and resets; picking the ✓ one again removes it', async () => {
    phone()
    stubRegions(() => new Response(JSON.stringify(TREE), { status: 200 }))
    const onToggle = vi.fn()
    renderGrid({ onToggle })
    const user = userEvent.setup()

    const sigungu = (await screen.findByRole('combobox', { name: '사는 곳 시군구' })) as HTMLSelectElement
    await user.selectOptions(sigungu, '마포구')

    expect(onToggle).toHaveBeenCalledWith(1114)
    expect(sigungu.value).toBe('')
    expect(within(screen.getByRole('list', { name: '고른 사는 곳' })).getByText('서울 마포구')).toBeTruthy()
    expect(optionTexts(sigungu)).toEqual(['시군구 고르기', '✓ 마포구', '중구'])

    await user.selectOptions(sigungu, '✓ 마포구')
    expect(onToggle).toHaveBeenLastCalledWith(1114)
    expect(onToggle).toHaveBeenCalledTimes(2)
    expect(sigungu.value).toBe('')
    expect(screen.queryByRole('list', { name: '고른 사는 곳' })).toBeNull()
    expect(optionTexts(sigungu)).toEqual(['시군구 고르기', '마포구', '중구'])
  })

  it('at the limit the not-chosen options are disabled and the limit line shows', async () => {
    phone()
    stubRegions(() => new Response(JSON.stringify(TREE), { status: 200 }))
    renderGrid({ initial: [1114], full: true })

    await screen.findByRole('combobox', { name: '사는 곳 시군구' })
    expect((screen.getByRole('option', { name: '중구' }) as HTMLOptionElement).disabled).toBe(true)
    expect((screen.getByRole('option', { name: '✓ 마포구' }) as HTMLOptionElement).disabled).toBe(false)
    expect(screen.getByText('최대 20곳이에요')).toBeTruthy()
  })

  it('no limit line below the limit', async () => {
    phone()
    stubRegions(() => new Response(JSON.stringify(TREE), { status: 200 }))
    renderGrid({ initial: [1114] })

    await screen.findByRole('combobox', { name: '사는 곳 시군구' })
    expect((screen.getByRole('option', { name: '중구' }) as HTMLOptionElement).disabled).toBe(false)
    expect(screen.queryByText('최대 20곳이에요')).toBeNull()
  })

  it('loading and failure lines are the same as on PC', async () => {
    phone()
    let fail = true
    stubRegions(() => (fail ? new Response('{}', { status: 500 }) : new Response(JSON.stringify(TREE), { status: 200 })))
    renderGrid()

    expect(screen.getByText('불러오는 중…')).toBeTruthy()
    const alert = await screen.findByRole('alert')
    expect(alert.textContent).toBe('지역 목록을 불러오지 못했어요 다시 시도')
    fail = false
    await userEvent.setup().click(within(alert).getByRole('button', { name: '다시 시도' }))
    expect(await screen.findByRole('combobox', { name: '사는 곳 시도' })).toBeTruthy()
  })
})

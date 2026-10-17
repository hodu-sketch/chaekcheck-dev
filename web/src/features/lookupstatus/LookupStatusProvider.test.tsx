import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { LOOKUP_STATUS_POLL_SECONDS, LOOKUP_STATUS_STALE_MS, type LookupStatus } from '../../api/lookupStatus'
import { HeaderOpenProvider } from '../../components/HeaderOpen'
import { HeaderSignal } from './HeaderSignal'
import { LookupStatusProvider } from './LookupStatusProvider'
import { LEVEL_ARIA, LEVEL_LABEL, secondLine, type LookupView } from './lookupStatusText'
import { signalServerBusy } from './serverBusy'

const POLL_MS = LOOKUP_STATUS_POLL_SECONDS * 1000

function status(overrides: Partial<LookupStatus> = {}): LookupStatus {
  return {
    slots: 40,
    inUse: 0,
    level: 'GREEN',
    reason: 'NONE',
    queuedLibraryIds: [],
    serverTime: '2026-10-17T00:00:00Z',
    ...overrides,
  }
}

const ok = (body: LookupStatus) => () =>
  Promise.resolve(new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } }))
const down = () => Promise.reject(new TypeError('Failed to fetch'))

let fetchMock: ReturnType<typeof vi.fn>

function setVisibility(state: 'visible' | 'hidden') {
  Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => state })
  // TanStack's focusManager listens on window, the provider on document; the real event bubbles to both
  document.dispatchEvent(new Event('visibilitychange', { bubbles: true }))
}

function renderSignal() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={client}>
      <LookupStatusProvider>
        <HeaderOpenProvider>
          <HeaderSignal />
        </HeaderOpenProvider>
      </LookupStatusProvider>
    </QueryClientProvider>,
  )
}

/** Lets the pending fetch answer and React settle; with fake timers, also moves the clock by `ms`. */
async function settle(ms = 0) {
  await act(async () => {
    await vi.advanceTimersByTimeAsync(ms)
    // an answer that landed at the end of `ms` still has its body to read
    for (let i = 0; i < 5; i++) await vi.advanceTimersByTimeAsync(1)
  })
}

/** Opens the popover (Radix renders it in a portal on the next tick) and returns it. */
async function openPopover() {
  fireEvent.click(dot())
  await settle()
  return screen.getByRole('dialog')
}

const dot = () => screen.getByRole('button', { name: /조회 상태/ })
const live = () => screen.getByRole('status')

beforeEach(() => {
  vi.useFakeTimers()
  fetchMock = vi.fn(ok(status()))
  vi.stubGlobal('fetch', fetchMock)
  // Radix positions popovers with ResizeObserver, which jsdom does not have
  globalThis.ResizeObserver ??= class {
    observe() {}
    unobserve() {}
    disconnect() {}
  }
})

afterEach(() => {
  cleanup()
  setVisibility('visible')
  vi.unstubAllGlobals()
  vi.useRealTimers()
})

/** Handoff 1-12 text table (AC-1, AC-2, AC-27, AC-37) [확정]. */
describe('color and reason → text', () => {
  const server = (level: LookupStatus['level'], reason: LookupStatus['reason'] = 'NONE'): LookupView => ({
    level,
    source: 'server',
    reason,
  })

  it.each([
    ['GREEN', '조회 원활', '조회 상태: 원활'],
    ['YELLOW', '조회 붐빔', '조회 상태: 붐빔'],
    ['RED', '조회 혼잡', '조회 상태: 혼잡'],
    ['GRAY', '상태 모름', '조회 상태: 모름'],
  ] as const)('%s reads %s and is named %s', (level, label, aria) => {
    expect(LEVEL_LABEL[level]).toBe(label)
    expect(LEVEL_ARIA[level]).toBe(aria)
  })

  it.each<[string, LookupView, string]>([
    ['green', server('GREEN'), '지금 바로 찾을 수 있어요'],
    ['yellow', server('YELLOW'), '찾는 사람이 늘고 있어요. 조금 느릴 수 있어요'],
    ['red', server('RED'), '지금 찾는 사람이 많아 검색이 거절될 수 있어요'],
    ['gray without an answer', { level: 'GRAY', source: 'stale' }, '조회 상태를 받아오지 못했어요'],
    ['gray from the server (DB error)', server('GRAY', 'DB_ERROR'), '상태를 확인할 수 없어요'],
    ['yellow + VENDOR_PAUSED', server('YELLOW', 'VENDOR_PAUSED'), '지금은 조회가 평소보다 느려요'],
    ['red + NO_LANE', server('RED', 'NO_LANE'), '지금은 도서관 조회를 할 수 없어요'],
    ['red after a 503', { level: 'RED', source: 'busy' }, '지금 찾는 사람이 많아 검색이 거절될 수 있어요'],
  ])('second line for %s', (_name, view, line) => {
    expect(secondLine(view)).toBe(line)
  })
})

describe('header light', () => {
  it('draws nothing before the first answer, then the dot, its name and the polite text', async () => {
    renderSignal()
    expect(screen.queryByRole('button')).toBeNull()
    expect(screen.queryByRole('status')).toBeNull()
    await settle()
    expect(dot().getAttribute('aria-label')).toBe('조회 상태: 원활')
    expect(dot().textContent).toBe('조회 원활')
    expect(live().textContent).toBe('조회 원활')
  })

  it('asks every 10 seconds and shows the latest level', async () => {
    renderSignal()
    await settle()
    expect(fetchMock).toHaveBeenCalledTimes(1)
    fetchMock.mockImplementation(ok(status({ level: 'YELLOW', inUse: 25 })))
    await settle(POLL_MS)
    expect(fetchMock).toHaveBeenCalledTimes(2)
    expect(dot().getAttribute('aria-label')).toBe('조회 상태: 붐빔')
  })

  it('opens the popover on a click with the label and the second line, no title tooltip', async () => {
    renderSignal()
    await settle()
    expect(dot().getAttribute('title')).toBeNull()
    const popover = await openPopover()
    expect(popover.textContent).toContain('조회 원활')
    expect(popover.textContent).toContain('지금 바로 찾을 수 있어요')
  })

  it('turns red at once on a 503 and takes the next answer again (AC-3)', async () => {
    renderSignal()
    await settle()
    act(() => signalServerBusy())
    expect(dot().getAttribute('aria-label')).toBe('조회 상태: 혼잡')
    expect((await openPopover()).textContent).toContain('지금 찾는 사람이 많아 검색이 거절될 수 있어요')
    await settle(POLL_MS)
    expect(fetchMock).toHaveBeenCalledTimes(2)
    expect(dot().getAttribute('aria-label')).toBe('조회 상태: 원활')
  })

  it('stops asking while the tab is hidden and asks once at once when it shows again (AC-6)', async () => {
    renderSignal()
    await settle()
    expect(fetchMock).toHaveBeenCalledTimes(1)
    act(() => setVisibility('hidden'))
    await settle(POLL_MS * 2.5)
    expect(fetchMock).toHaveBeenCalledTimes(1)
    act(() => setVisibility('visible'))
    await settle()
    expect(fetchMock).toHaveBeenCalledTimes(2)
  })

  it('keeps the last level for 30 seconds of failures, then is gray 「상태 모름」 (AC-42)', async () => {
    renderSignal()
    await settle()
    fetchMock.mockImplementation(down)
    await settle(POLL_MS * 2)
    expect(fetchMock).toHaveBeenCalledTimes(3)
    expect(dot().getAttribute('aria-label')).toBe('조회 상태: 원활')
    await settle(LOOKUP_STATUS_STALE_MS - POLL_MS * 2)
    expect(dot().getAttribute('aria-label')).toBe('조회 상태: 모름')
    expect(dot().textContent).toBe('상태 모름')
    expect((await openPopover()).textContent).toContain('조회 상태를 받아오지 못했어요')
  })

  it('is gray at once after more than 30 seconds hidden when the first ask back fails (AC-42)', async () => {
    renderSignal()
    await settle()
    act(() => setVisibility('hidden'))
    await settle(LOOKUP_STATUS_STALE_MS + 5_000)
    fetchMock.mockImplementation(down)
    act(() => setVisibility('visible'))
    await settle()
    expect(dot().getAttribute('aria-label')).toBe('조회 상태: 모름')
  })

  it('gray from the server says the state cannot be checked, and beats a 503 (AC-37)', async () => {
    fetchMock.mockImplementation(ok(status({ level: 'GRAY', reason: 'DB_ERROR' })))
    renderSignal()
    await settle()
    act(() => signalServerBusy())
    expect(dot().getAttribute('aria-label')).toBe('조회 상태: 모름')
    expect((await openPopover()).textContent).toContain('상태를 확인할 수 없어요')
  })

  it('changes the polite text only when the level changes (AC-20)', async () => {
    renderSignal()
    await settle()
    const node = live().firstChild
    fetchMock.mockImplementation(ok(status({ inUse: 3, queuedLibraryIds: [7] })))
    await settle(POLL_MS)
    // the same level again: the same text node, nothing to read again
    expect(live().firstChild).toBe(node)
    expect(live().textContent).toBe('조회 원활')
    fetchMock.mockImplementation(ok(status({ level: 'RED', inUse: 40 })))
    await settle(POLL_MS)
    expect(live().textContent).toBe('조회 혼잡')
    expect(dot().getAttribute('aria-label')).toBe('조회 상태: 혼잡')
  })
})

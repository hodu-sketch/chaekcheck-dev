import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { ReactNode } from 'react'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router'
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { loadChat } from './aiChatStore'
import { EXAMPLE_QUESTIONS, QUESTION_MAX } from './aiSearchText'
import { SearchTabs } from './SearchTabs'

beforeAll(() => {
  // Radix positions menus with ResizeObserver, which jsdom does not have
  globalThis.ResizeObserver ??= class {
    observe() {}
    unobserve() {}
    disconnect() {}
  }
})

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

beforeEach(() => sessionStorage.clear())

function Where() {
  const location = useLocation()
  return <p data-testid="where">{location.pathname + location.search}</p>
}

/** The direct form of the page, reduced to its box: the leading menu, then the title box. */
const directForm = (leading: ReactNode, hidden: boolean) => (
  <div hidden={hidden}>
    {leading}
    <input aria-label="책 제목" />
  </div>
)

function setup(statusCode = 200, { showTabs = true, state }: { showTabs?: boolean; state?: unknown } = {}) {
  vi.stubGlobal(
    'fetch',
    vi.fn(async () => new Response(statusCode === 200 ? '{}' : '', { status: statusCode })),
  )
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[{ pathname: '/', state }]}>
        <Routes>
          <Route path="/" element={<SearchTabs showTabs={showTabs} direct={directForm} />} />
          <Route path="/ai-search" element={<Where />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
  return userEvent.setup()
}

const aiBox = () => screen.getByLabelText('찾는 책을 문장으로') as HTMLInputElement
const titleBox = () => screen.getByLabelText('책 제목') as HTMLInputElement
const shown = (element: HTMLElement) => !element.closest('[hidden]')

async function choose(user: ReturnType<typeof userEvent.setup>, from: string, to: string) {
  const triggers = screen.getAllByRole('button', { name: `찾는 방법: ${from}` }).filter(shown)
  await user.click(triggers[0])
  await user.click(screen.getByRole('menuitemradio', { name: to }))
}

describe('SearchTabs: the AI/직접 menu in the box (user 2026-10-24)', () => {
  it('opens on AI, its menu at the left end of the AI box', () => {
    setup()
    expect(shown(aiBox())).toBe(true)
    expect(shown(titleBox())).toBe(false)
    const trigger = screen.getAllByRole('button', { name: '찾는 방법: AI' }).filter(shown)[0]
    expect(trigger.parentElement?.contains(aiBox())).toBe(true)
  })

  it('switches to the direct search and back, keeping each text', async () => {
    const user = setup()
    await user.type(aiBox(), '과학책')
    await choose(user, 'AI', '직접')
    expect(shown(titleBox())).toBe(true)
    expect(screen.getAllByRole('button', { name: '찾는 방법: 직접' }).filter(shown)).toHaveLength(1)
    await user.type(titleBox(), '토지')
    await choose(user, '직접', 'AI')
    expect(aiBox().value).toBe('과학책')
    expect(titleBox().value).toBe('토지')
  })

  it('does not send a question under two characters and caps the box at 200', async () => {
    const user = setup()
    expect(aiBox().maxLength).toBe(QUESTION_MAX)
    await user.type(aiBox(), ' 책 ')
    await user.click(screen.getByRole('button', { name: '찾기' }))
    expect(screen.getByRole('alert').textContent).toBe('두 글자 이상 써 주세요')
    expect(loadChat().turns).toHaveLength(0)
  })

  it('opens /ai-search without the question in the address', async () => {
    const user = setup()
    await user.type(aiBox(), '잠들기 전에 읽을 과학책')
    await user.click(screen.getByRole('button', { name: '찾기' }))
    expect(screen.getByTestId('where').textContent).toBe('/ai-search')
    expect(loadChat().turns.map((turn) => turn.question)).toEqual(['잠들기 전에 읽을 과학책'])
  })

  it('searches a chip at once', async () => {
    const user = setup()
    await user.click(screen.getByRole('button', { name: EXAMPLE_QUESTIONS[0] }))
    expect(screen.getByTestId('where').textContent).toBe('/ai-search')
    expect(loadChat().turns[0].question).toBe(EXAMPLE_QUESTIONS[0])
  })

  it('shows only the direct search, without the menu, when the AI search is off', async () => {
    setup(404)
    await waitFor(() => expect(screen.queryByRole('button', { name: /찾는 방법/ })).toBeNull())
    expect(shown(titleBox())).toBe(true)
  })

  it('shows the direct search only on the results screen', () => {
    setup(200, { showTabs: false })
    expect(screen.queryByRole('button', { name: /찾는 방법/ })).toBeNull()
    expect(shown(titleBox())).toBe(true)
  })

  it('opens on the direct search when the chat sends the user there', () => {
    setup(200, { state: { searchTab: 'direct' } })
    expect(shown(titleBox())).toBe(true)
    expect(screen.getAllByRole('button', { name: '찾는 방법: 직접' }).filter(shown)).toHaveLength(1)
  })
})

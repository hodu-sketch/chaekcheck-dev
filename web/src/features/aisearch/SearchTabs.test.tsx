import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { loadChat } from './aiChatStore'
import { EXAMPLE_QUESTIONS, QUESTION_MAX } from './aiSearchText'
import { SearchTabs } from './SearchTabs'

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

beforeEach(() => sessionStorage.clear())

function Where() {
  const location = useLocation()
  return <p data-testid="where">{location.pathname + location.search}</p>
}

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
          <Route path="/" element={<SearchTabs showTabs={showTabs} direct={<input aria-label="책 제목" />} />} />
          <Route path="/ai-search" element={<Where />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
  return userEvent.setup()
}

const aiBox = () => screen.getByRole('searchbox', { name: '찾는 책을 문장으로' }) as HTMLInputElement

describe('SearchTabs (cycle 24 step 9)', () => {
  it('opens on the AI tab, on the left', () => {
    setup()
    const tabs = screen.getAllByRole('tab')
    expect(tabs.map((tab) => tab.textContent)).toEqual(['AI', '직접'])
    expect(tabs[0].getAttribute('aria-selected')).toBe('true')
    expect(aiBox()).toBeTruthy()
  })

  it('switches to the direct search and keeps each tab text', async () => {
    const user = setup()
    await user.type(aiBox(), '과학책')
    await user.click(screen.getByRole('tab', { name: '직접' }))
    const title = screen.getByRole('textbox', { name: '책 제목' }) as HTMLInputElement
    expect(title.closest('[role=tabpanel]')?.hasAttribute('hidden')).toBe(false)
    await user.type(title, '토지')
    await user.click(screen.getByRole('tab', { name: 'AI' }))
    expect(aiBox().value).toBe('과학책')
    expect(title.value).toBe('토지')
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

  it('shows only the direct search when the AI search is off', async () => {
    setup(404)
    await waitFor(() => expect(screen.queryByRole('tab')).toBeNull())
    expect(screen.getByRole('textbox', { name: '책 제목' })).toBeTruthy()
  })

  it('shows the direct search only on the results screen, keeping the same form element', async () => {
    setup(200, { showTabs: false })
    expect(screen.queryByRole('tab')).toBeNull()
    expect(screen.getByRole('textbox', { name: '책 제목' })).toBeTruthy()
  })

  it('opens on the direct tab when the chat sends the user there', () => {
    setup(200, { state: { searchTab: 'direct' } })
    expect(screen.getByRole('tab', { name: '직접' }).getAttribute('aria-selected')).toBe('true')
  })
})

import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { aiSearchStream, type AiSearchBody, type AiStreamEvent } from '../../api/aiSearch'
import { ApiError } from '../../api/client'
import { AiChatPage } from './AiChatPage'
import { addQuestion, EMPTY_CHAT, loadChat, saveChat, startWithQuestion, type AiChat } from './aiChatStore'
import { TURNS_MAX } from './aiSearchText'

vi.mock('../region/RegionContext', () => ({
  useRegions: () => ({ regions: { residence: [1116], work: [] }, libraryIds: [12], source: 'browser' }),
}))
vi.mock('../search/regionPlace', () => ({ useMineLabel: () => '마포구', useTagPlace: () => null }))
vi.mock('../../api/aiSearch', () => ({ aiSearchStream: vi.fn() }))

const stream = vi.mocked(aiSearchStream)

afterEach(cleanup)

beforeEach(() => {
  sessionStorage.clear()
  stream.mockReset()
})

const CARD = {
  no: 1,
  bookId: 7,
  title: '코스모스',
  author: '칼 세이건',
  coverUrl: null,
  myLibraryCount: 2,
  aiSummary: '우주의 역사를 쉽게 풀어 쓴 교양서예요.',
}

/** The model answers with these events, then the call returns. */
function answers(...events: AiStreamEvent[]) {
  stream.mockImplementation(async (_body, onEvent) => {
    events.forEach(onEvent)
  })
}


let client: QueryClient

function Where() {
  const location = useLocation()
  return <p data-testid="where">{location.pathname + location.search + JSON.stringify(location.state ?? null)}</p>
}

function setup() {
  client = new QueryClient()
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={['/ai-search']}>
        <Routes>
          <Route path="/ai-search" element={<AiChatPage />} />
          <Route path="*" element={<Where />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
  return userEvent.setup()
}

const okAnswer: AiStreamEvent[] = [
  { name: 'head-delta', data: { text: '우주 이야기 책이에요' } },
  { name: 'card', data: CARD },
  { name: 'card-delta', data: { bookId: 7, text: '별과 ' } },
  { name: 'card-delta', data: { bookId: 7, text: '우주를 다뤄요' } },
  { name: 'done', data: { result: 'ok', bookIds: [7] } },
]

describe('AiChatPage (cycle 24 step 10)', () => {
  it('sends the first screen question and shows the streamed answer as plain text', async () => {
    startWithQuestion('우주 책')
    answers(...okAnswer)
    setup()

    expect(await screen.findByText('별과 우주를 다뤄요')).toBeTruthy()
    expect(screen.getByText('우주 이야기 책이에요')).toBeTruthy()
    expect(screen.getByRole('button', { name: '코스모스' })).toBeTruthy()
    expect(screen.getByText('내 도서관 2곳')).toBeTruthy()
    const body = stream.mock.calls[0][0] as AiSearchBody
    expect(body).toMatchObject({ question: '우주 책', turnNo: 1, history: [], residenceRegionIds: [1116], libraryIds: [12] })
    await waitFor(() => expect(loadChat().turns[0].state).toBe('done'))
  })

  it('sends a follow-up with the earlier turn and its books', async () => {
    startWithQuestion('우주 책')
    answers(...okAnswer)
    const user = setup()
    await screen.findByText('별과 우주를 다뤄요')

    // no hint in the box once the conversation has started (user 2026-10-24)
    expect(screen.getByRole('textbox', { name: '이어서 묻기' }).hasAttribute('placeholder')).toBe(false)
    await user.type(screen.getByRole('textbox', { name: '이어서 묻기' }), '더 쉬운 것')
    await user.click(screen.getByRole('button', { name: '보내기' }))

    await waitFor(() => expect(stream).toHaveBeenCalledTimes(2))
    expect(stream.mock.calls[1][0]).toMatchObject({
      question: '더 쉬운 것',
      turnNo: 2,
      history: [{ question: '우주 책', bookIds: [7] }],
    })
  })

  it('keeps the conversation across a reload and a new chat clears it', async () => {
    startWithQuestion('우주 책')
    answers(...okAnswer)
    const user = setup()
    await screen.findByText('별과 우주를 다뤄요')
    cleanup()

    setup()
    expect(screen.getByText('별과 우주를 다뤄요')).toBeTruthy()
    expect(stream).toHaveBeenCalledTimes(1)

    await user.click(screen.getByRole('button', { name: '새 대화' }))
    expect(screen.queryByText('우주 책')).toBeNull()
    expect(loadChat().turns).toHaveLength(0)
  })

  it('asks for a new chat after twenty questions', () => {
    let chat: AiChat = EMPTY_CHAT
    for (let i = 0; i < TURNS_MAX; i++) chat = addQuestion(chat, `질문 ${i}`)
    saveChat({ turns: chat.turns.map((turn) => ({ ...turn, state: 'done', result: 'no_match' })) })
    setup()
    expect(screen.getByText('새 대화를 시작해 주세요')).toBeTruthy()
    expect(screen.queryByRole('textbox', { name: '이어서 묻기' })).toBeNull()
  })

  it('shows the cards it has when the answer breaks off', async () => {
    startWithQuestion('우주 책')
    answers({ name: 'card', data: CARD }, { name: 'error', data: { code: 'llm_failed' } })
    setup()
    expect(await screen.findByText('지금은 설명을 쓰지 못했어요')).toBeTruthy()
    expect(screen.getByRole('button', { name: '코스모스' })).toBeTruthy()
  })

  it('says to wait on 429 and to use the direct search when paused', async () => {
    startWithQuestion('우주 책')
    stream.mockRejectedValueOnce(new ApiError(429, { title: 'Too Many', status: 429, code: 'too_many_requests' }))
    const user = setup()
    expect(await screen.findByText('잠시 뒤에 다시 써 주세요')).toBeTruthy()
    cleanup()

    sessionStorage.clear()
    startWithQuestion('우주 책')
    stream.mockRejectedValueOnce(new ApiError(503, { title: 'Paused', status: 503, code: 'ai_search_paused' }))
    setup()
    expect(await screen.findByText('오늘은 AI 책 찾기를 쉬어요. 직접 찾기를 써 주세요')).toBeTruthy()
    await user.click(screen.getByRole('button', { name: '직접 찾기로' }))
    expect(screen.getByTestId('where').textContent).toBe('/{"searchTab":"direct"}')
  })

  it('offers the guessed title in the direct search', async () => {
    startWithQuestion('토지 있어?')
    answers({ name: 'title-guess', data: { title: '토지' } }, { name: 'done', data: { result: 'ok', bookIds: [] } })
    const user = setup()
    expect(await screen.findByText(/「토지」을\(를\) 찾으시나요\?/)).toBeTruthy()
    await user.click(screen.getByRole('button', { name: '직접 찾기에서 보기' }))
    expect(screen.getByTestId('where').textContent).toBe('/search?title=%ED%86%A0%EC%A7%80null')
  })

  it('hides the AI tab for this page load when the AI search was turned off', async () => {
    startWithQuestion('우주 책')
    stream.mockRejectedValueOnce(new ApiError(404, { title: 'Not Found', status: 404, code: 'not_found' }))
    setup()
    expect(await screen.findByText('오늘은 AI 책 찾기를 쉬어요. 직접 찾기를 써 주세요')).toBeTruthy()
    expect(client.getQueryData(['ai-search-status'])).toBe('off')
  })

  it('does not add a question while an answer is still coming', async () => {
    startWithQuestion('우주 책')
    stream.mockImplementation(() => new Promise(() => {}))
    const user = setup()
    await waitFor(() => expect(stream).toHaveBeenCalledTimes(1))
    await user.type(screen.getByRole('textbox', { name: '이어서 묻기' }), '더 쉬운 것{Enter}')
    expect(loadChat().turns).toHaveLength(1)
  })

  it('opens the recommended books\' book window from a card (user 2026-10-24)', async () => {
    const detail = {
      id: 7,
      title: '코스모스',
      authorDisplay: '칼 세이건',
      publisher: '사이언스북스',
      publishedOn: '2006-12-20',
      isbn13: '9788983711892',
      holdingLibraryCount: 0,
      libraries: [],
    }
    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: string) =>
        String(input).startsWith('/api/v1/books/7')
          ? new Response(JSON.stringify(detail), { status: 200, headers: { 'Content-Type': 'application/json' } })
          : new Response('{}', { status: 400 }),
      ),
    )
    startWithQuestion('우주 책')
    answers(...okAnswer)
    const user = setup()
    await user.click(await screen.findByRole('button', { name: '코스모스' }))
    const dialog = await screen.findByRole('dialog', { name: '코스모스 대출 정보' })
    // the recommended lists' AI summary, as in the picks book window (user 2026-10-24)
    expect(await within(dialog).findByText('우주의 역사를 쉽게 풀어 쓴 교양서예요.')).toBeTruthy()
    vi.unstubAllGlobals()
  })

  it('sends with Enter, starts a new line with Shift+Enter and takes 200 characters at most (user 2026-10-24)', async () => {
    startWithQuestion('우주 책')
    answers(...okAnswer)
    const user = setup()
    await screen.findByText('별과 우주를 다뤄요')
    const box = screen.getByRole('textbox', { name: '이어서 묻기' }) as HTMLTextAreaElement
    expect(box.maxLength).toBe(200)

    await user.type(box, '소설만{Shift>}{Enter}{/Shift}짧은 걸로')
    expect(box.value).toBe('소설만\n짧은 걸로')
    await user.type(box, '{Enter}')

    await waitFor(() => expect(stream).toHaveBeenCalledTimes(2))
    expect(stream.mock.calls[1][0].question).toBe('소설만\n짧은 걸로')
  })

  it('starts a new conversation with the [+] at the left of the box', async () => {
    startWithQuestion('우주 책')
    answers(...okAnswer)
    const user = setup()
    await screen.findByText('별과 우주를 다뤄요')
    await user.click(screen.getByRole('button', { name: '새 대화' }))
    expect(screen.queryByText('우주 책')).toBeNull()
  })
})

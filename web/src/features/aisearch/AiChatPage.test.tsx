import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { aiSearchStream, fetchAiLibraryCounts, type AiSearchBody, type AiStreamEvent } from '../../api/aiSearch'
import { ApiError } from '../../api/client'
import { AiChatPage } from './AiChatPage'
import { addQuestion, EMPTY_CHAT, loadChat, saveChat, startWithQuestion, type AiChat } from './aiChatStore'
import { shownExamples, TURNS_MAX } from './aiSearchText'

const MAPO = { regions: { residence: [1116], work: [] as number[] }, libraryIds: [12], source: 'browser' }
const regionState = vi.hoisted(() => ({ current: null as unknown }))
vi.mock('../region/RegionContext', () => ({ useRegions: () => regionState.current }))
vi.mock('../search/regionPlace', () => ({ useMineLabel: () => '마포구', useTagPlace: () => null }))
vi.mock('../../api/aiSearch', () => ({ aiSearchStream: vi.fn(), fetchAiLibraryCounts: vi.fn() }))

const stream = vi.mocked(aiSearchStream)
const counts = vi.mocked(fetchAiLibraryCounts)

afterEach(cleanup)

beforeEach(() => {
  regionState.current = MAPO
  sessionStorage.clear()
  stream.mockReset()
  counts.mockReset()
  counts.mockResolvedValue({})
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

  it('shows the agent asking back with buttons; a click asks that answer next (user 2026-10-26)', async () => {
    startWithQuestion('llm인가')
    answers(
      { name: 'ask', data: { question: '어느 쪽을 찾으세요?', choices: ['LLM의 원리를 다룬 책', 'LLM 활용법'] } },
      { name: 'done', data: { result: 'asked', bookIds: [] } },
    )
    const user = setup()

    expect(await screen.findByText('어느 쪽을 찾으세요?')).toBeTruthy()
    const choices = within(screen.getByRole('list', { name: '고를 답' })).getAllByRole('button')
    // numbered rows, then a box for one's own answer, no 「기타」 button (user 2026-10-29 안 A)
    expect(choices.map((button) => button.querySelector('span:last-child')?.textContent)).toEqual([
      'LLM의 원리를 다룬 책',
      'LLM 활용법',
    ])
    expect(screen.getByRole('textbox', { name: '직접 적어서 묻기' })).toBeTruthy()
    expect(screen.queryByText(/맞는 책을 찾지 못했어요/)).toBeNull()

    answers(...okAnswer)
    await user.click(choices[0])

    await waitFor(() => expect(stream).toHaveBeenCalledTimes(2))
    expect(stream.mock.calls[1][0]).toMatchObject({
      question: 'LLM의 원리를 다룬 책',
      turnNo: 2,
      history: [
        { question: 'llm인가', bookIds: [], askQuestion: '어느 쪽을 찾으세요?', askChoices: ['LLM의 원리를 다룬 책', 'LLM 활용법'] },
      ],
    })
    // the earlier question's buttons are answered now
    await screen.findByText('별과 우주를 다뤄요')
    expect(
      within(screen.getByRole('list', { name: '고를 답' }))
        .getAllByRole('button')
        .every((button) => (button as HTMLButtonElement).disabled),
    ).toBe(true)
  })

  it('the list\'s last row sends what is written as the next question (user 2026-10-29 안 A)', async () => {
    startWithQuestion('사진')
    answers(
      { name: 'ask', data: { question: '어떤 쪽을 찾으세요?', choices: ['촬영 기법', '사진집'] } },
      { name: 'done', data: { result: 'asked', bookIds: [] } },
    )
    const user = setup()
    await screen.findByText('어떤 쪽을 찾으세요?')
    const box = screen.getByRole('textbox', { name: '직접 적어서 묻기' })
    // the send button comes with the first letter
    const list = screen.getByRole('list', { name: '고를 답' })
    expect(within(list).queryByRole('button', { name: '보내기' })).toBeNull()
    answers(...okAnswer)
    await user.type(box, '필름 카메라 이야기{Enter}')

    await waitFor(() => expect(stream).toHaveBeenCalledTimes(2))
    expect(stream.mock.calls[1][0]).toMatchObject({ question: '필름 카메라 이야기', turnNo: 2 })
  })

  it('shows the summary by situation under the cards and sends the head back with its turn (user 2026-10-29)', async () => {
    startWithQuestion('우주 책')
    answers(...okAnswer.slice(0, -1), { name: 'tail-delta', data: { text: '밤하늘을 볼 때: 『우주』' } }, okAnswer.at(-1)!)
    const user = setup()
    // a heading, then the situation in bold over its books (user 2026-10-29 mock-up)
    expect(await screen.findByText('상황별로 고르면')).toBeTruthy()
    expect(screen.getByText('밤하늘을 볼 때')).toBeTruthy()
    expect(screen.getByText('『우주』')).toBeTruthy()

    answers(...okAnswer)
    await user.type(screen.getByLabelText('이어서 묻기'), '더 쉬운 거{Enter}')
    await waitFor(() => expect(stream).toHaveBeenCalledTimes(2))
    expect(stream.mock.calls[1][0].history).toEqual([{ question: '우주 책', bookIds: [7], head: '우주 이야기 책이에요' }])
  })

  it('keeps the model head of an answer with no book; the fixed line only when it is empty (user 2026-10-29)', async () => {
    startWithQuestion('더 쉬운 거')
    answers(
      { name: 'head-delta', data: { text: '더 쉬운 책은 찾기 어려웠어요.' } },
      { name: 'done', data: { result: 'no_match', bookIds: [] } },
    )
    setup()
    expect(await screen.findByText('더 쉬운 책은 찾기 어려웠어요.')).toBeTruthy()
    expect(screen.queryByText(/맞는 책을 찾지 못했어요/)).toBeNull()
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

  it('counts the plan libraries again for the books shown, with no new search (user 2026-10-25)', async () => {
    startWithQuestion('우주 책')
    answers(...okAnswer)
    setup()
    expect(await screen.findByText('내 도서관 2곳')).toBeTruthy()
    cleanup()

    // the region was changed on another page: the page counts again when it comes back
    regionState.current = { ...MAPO, regions: { residence: [1116, 1117], work: [] } }
    counts.mockResolvedValue({ '7': 5 })
    setup()
    expect(await screen.findByText('내 도서관 5곳')).toBeTruthy()
    expect(counts.mock.calls.at(-1)![0]).toMatchObject({ bookIds: [7], residenceRegionIds: [1116, 1117], libraryIds: [12] })
    expect(stream).toHaveBeenCalledTimes(1)
    expect(loadChat().turns[0].cards[0].myLibraryCount).toBe(5)
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

  it('shows an answer about 책첵 with the way to the intro tab and no examples (user 2026-10-26)', async () => {
    startWithQuestion('책첵은 무료야?')
    answers({ name: 'head-delta', data: { text: '네, 책첵은 무료예요.' } }, { name: 'done', data: { result: 'site', bookIds: [] } })
    const user = setup()
    expect(await screen.findByText('네, 책첵은 무료예요.')).toBeTruthy()
    expect(screen.getByText('답은 책첵 소개와 첫 화면 안내를 바탕으로 AI가 썼어요.')).toBeTruthy()
    expect(screen.queryByText('맞는 책을 찾지 못했어요. 다른 말로 써 보시겠어요?')).toBeNull()
    await user.click(screen.getByRole('link', { name: '책첵이란? 자세히 보기 →' }))
    expect(screen.getByTestId('where').textContent).toContain('/about')
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

  it('starts a new conversation with [새 대화] at the top right, none on an empty one (user 2026-10-24)', async () => {
    startWithQuestion('우주 책')
    answers(...okAnswer)
    const user = setup()
    await screen.findByText('별과 우주를 다뤄요')
    await user.click(screen.getByRole('button', { name: '새 대화' }))
    expect(screen.queryByText('우주 책')).toBeNull()
    expect(screen.queryByRole('button', { name: '새 대화' })).toBeNull()
  })

  it('says where it looks when no region is chosen, under the latest answer (user 2026-10-24)', async () => {
    startWithQuestion('우주 책')
    answers(...okAnswer)
    setup()
    await screen.findByText('별과 우주를 다뤄요')
    expect(screen.queryByText(/누구나 빌릴 수 있는 도서관에서만 찾고 있어요/)).toBeNull()
    cleanup()
    regionState.current = { regions: { residence: [], work: [] }, libraryIds: [], source: 'browser' }
    setup()
    const note = await screen.findByText(/누구나 빌릴 수 있는 도서관에서만 찾고 있어요/)
    // after the answer's cards, inside the turn
    expect(screen.getByText('별과 우주를 다뤄요').compareDocumentPosition(note) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    expect(screen.getByRole('link', { name: '마이페이지' }).getAttribute('href')).toBe('/mypage')
  })

  it('offers the example questions on an empty conversation, asked at once (user 2026-10-24)', async () => {
    answers(...okAnswer)
    const user = setup()
    await user.click(screen.getByRole('button', { name: shownExamples()[0] }))
    await waitFor(() => expect(stream).toHaveBeenCalledTimes(1))
    expect(stream.mock.calls[0][0].question).toBe(shownExamples()[0])
    expect(screen.queryByRole('list', { name: '예시 질문' })).toBeNull()
  })
})

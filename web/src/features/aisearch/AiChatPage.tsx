import { useQueryClient } from '@tanstack/react-query'
import { useEffect, useRef, useState, type FormEvent, type KeyboardEvent } from 'react'
import { Link, useNavigate } from 'react-router'
import { aiSearchStream, type AiSearchBody, type AiStreamEvent } from '../../api/aiSearch'
import { ApiError } from '../../api/client'
import type { PickBook } from '../../api/types'
import { useMediaQuery } from '../../lib/useMediaQuery'
import { PicksBookDialog } from '../picks/PicksBookDialog'
import { PHONE_QUERY } from '../picks/PicksPage'
import { usePicksPlan } from '../picks/usePicksPlan'
import { useRegions } from '../region/RegionContext'
import { SCOPE_TEXT } from '../search/LibraryScopeLine'
import { loadPrefs } from '../search/searchSettings'
import { addQuestion, EMPTY_CHAT, loadChat, saveChat, type AiChat, type AiTurnCard, type AiTurnState } from './aiChatStore'
import {
  AI_TEXT,
  EXAMPLES_SHOWN,
  HISTORY_SENT,
  QUESTION_MAX,
  QUESTION_MIN,
  shownExamples,
  TURNS_MAX,
} from './aiSearchText'
import type { SearchTabState } from './SearchTabs'
import styles from './AiChatPage.module.css'

/**
 * S30 `/ai-search` (cycle 24 step 10, flow S30): the conversation, questions on the right, answers on the left as a
 * head line and book cards that fill in as the answer streams. The conversation lives in this tab only (R11-6); every
 * question sends itself, the last 5 turns (question and shown books) and the plan values, in the body (ADR-0152). All
 * the model's text is shown as plain text, never as markup (step 8 security review #7).
 */
export function AiChatPage() {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const { regions, libraryIds, source } = useRegions()
  const noRegion =
    source !== 'waiting' && regions.residence.length === 0 && regions.work.length === 0 && libraryIds.length === 0
  const [chat, setChat] = useState<AiChat>(() => loadChat())
  const [draft, setDraft] = useState('')
  const [tooShort, setTooShort] = useState(false)
  const endRef = useRef<HTMLDivElement>(null)
  // the sticky header's height, so the [새 대화] bar sticks right under it
  const [headerHeight, setHeaderHeight] = useState(0)
  useEffect(() => {
    setHeaderHeight(document.querySelector('[data-app-header]')?.getBoundingClientRect().height ?? 0)
  }, [])
  // a card opens the recommended books' book window (user 2026-10-24 "추천 도서창에서 눌렀을 때처럼 모달")
  const [opened, setOpened] = useState<PickBook | null>(null)
  const { plan } = usePicksPlan()
  const phone = useMediaQuery(PHONE_QUERY)
  const newChatRef = useRef<HTMLButtonElement>(null)

  useEffect(() => saveChat(chat), [chat])

  const update = (id: string, change: (turn: AiTurnState) => AiTurnState) => {
    setChat((current) => ({ turns: current.turns.map((turn) => (turn.id === id ? change(turn) : turn)) }))
  }

  const waiting = chat.turns.find((turn) => turn.state === 'waiting')
  const busy = chat.turns.some((turn) => turn.state === 'waiting' || turn.state === 'streaming')

  // one request per turn; leaving the page stops the ones still open
  const requests = useRef(new Map<string, AbortController>())
  useEffect(() => {
    const open = requests.current
    return () => {
      open.forEach((controller) => controller.abort())
      open.clear()
    }
  }, [])

  // the waiting question is sent once the regions are known. The start waits one task, so StrictMode's dev-only
  // unmount and mount in between cancels it instead of sending the question twice.
  useEffect(() => {
    if (!waiting || source === 'waiting' || requests.current.has(waiting.id)) return
    const timer = setTimeout(() => send(waiting.id), 0)
    return () => clearTimeout(timer)
    // the request is for this waiting turn only; regions read once when it starts
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [waiting?.id, source])

  const send = (id: string) => {
    const controller = new AbortController()
    requests.current.set(id, controller)
    const index = chat.turns.findIndex((turn) => turn.id === id)
    const turn = chat.turns[index]
    if (!turn) return
    const earlier = chat.turns
      .slice(0, index)
      .filter((turn) => turn.state === 'done')
      .slice(-HISTORY_SENT)
      .map((turn) => ({ question: turn.question, bookIds: turn.cards.map((card) => card.bookId) }))
    const body: AiSearchBody = {
      question: turn.question,
      turnNo: Math.min(index + 1, TURNS_MAX),
      history: earlier,
      residenceRegionIds: regions.residence,
      workRegionIds: regions.work,
      libraryIds,
      scope: 'joinable',
      visit: loadPrefs().visit,
    }
    update(id, (current) => ({ ...current, state: 'streaming' }))
    aiSearchStream(body, (event) => update(id, (current) => apply(current, event)), controller.signal)
      .catch((error) => {
        if (controller.signal.aborted) return
        const state = refusal(error)
        // the AI search was turned off: the first screen shows the direct search only from now on
        if (error instanceof ApiError && error.status === 404) queryClient.setQueryData(['ai-search-status'], 'off')
        update(id, (current) => ({ ...current, state }))
      })
      .finally(() => {
        if (requests.current.get(id) === controller) requests.current.delete(id)
      })
  }

  useEffect(() => {
    endRef.current?.scrollIntoView?.({ block: 'end' })
  }, [chat.turns.length])

  const full = chat.turns.length >= TURNS_MAX

  useEffect(() => {
    if (full) newChatRef.current?.focus()
  }, [full])

  const ask = (question: string) => {
    if (busy || full) return
    const trimmed = question.trim()
    if ([...trimmed].length < QUESTION_MIN) {
      setTooShort(true)
      return
    }
    setChat((current) => addQuestion(current, trimmed))
    setDraft('')
    setTooShort(false)
  }

  const handleSubmit = (event: FormEvent) => {
    event.preventDefault()
    ask(draft)
  }

  // Enter sends, Shift+Enter starts a new line (user 2026-10-24); Enter that ends a Korean syllable only ends it
  const handleKeyDown = (event: KeyboardEvent<HTMLTextAreaElement>) => {
    if (event.key !== 'Enter' || event.shiftKey || event.nativeEvent.isComposing) return
    event.preventDefault()
    ask(draft)
  }

  const boxRef = useRef<HTMLTextAreaElement>(null)
  const nearLimit = draft.length >= QUESTION_MAX - 20

  // the box grows with its lines, up to the CSS max height, and goes back to one line once sent
  useEffect(() => {
    if (draft === '' && boxRef.current) boxRef.current.style.height = ''
  }, [draft])

  const newChat = () => {
    requests.current.forEach((controller) => controller.abort())
    requests.current.clear()
    setChat(EMPTY_CHAT)
    setDraft('')
  }

  return (
    // an empty conversation lays out as ChatGPT's new chat: title, the box in the middle, the examples under it
    <div className={styles.page} data-empty={chat.turns.length === 0 || undefined}>
      {chat.turns.length === 0 && <h1 className={styles.emptyTitle}>{AI_TEXT.emptyTitle}</h1>}
      {chat.turns.length > 0 && <h1 className="visually-hidden">AI 책 찾기</h1>}
      {/* [새 대화] moved to the [+] at the left of the box below (user 2026-10-24, "CHATGPT처럼") */}
      {/* no 「내 도서관: …」 line (user 2026-10-24: "여기서 내 도서관: 강서구 정보 빼") */}

      {chat.turns.length === 0 && (
        // a new conversation (the menu's AI 검색, the [+]): the example questions in the empty page (user 2026-10-24)
        <ul className={styles.startExamples} aria-label="예시 질문">
          {shownExamples()
            .slice(0, EXAMPLES_SHOWN)
            .map((question) => (
            <li key={question}>
              <button type="button" className={styles.example} onClick={() => ask(question)}>
                {question}
              </button>
            </li>
          ))}
        </ul>
      )}
      {chat.turns.length > 0 && (
        // [새 대화] at the top right, kept under the header while the conversation scrolls, as ChatGPT's new chat
        // (user 2026-10-24: the [+] in the box moved here, "대화 화면 오른쪽 위 단추")
        <div className={styles.chatBar} style={{ top: headerHeight }}>
          <button type="button" className={styles.newChatTop} onClick={newChat}>
            <svg width="16" height="16" viewBox="0 0 16 16" aria-hidden="true">
              <path
                d="M10.5 2.5l3 3L6 13H3v-3zM9 4l3 3"
                fill="none"
                stroke="currentColor"
                strokeWidth="1.5"
                strokeLinejoin="round"
              />
            </svg>
            {AI_TEXT.newChat}
          </button>
        </div>
      )}
      <ol className={styles.turns}>
        {chat.turns.map((turn, index) => (
          <li key={turn.id} className={styles.turn} aria-busy={turn.state === 'streaming' || undefined}>
            <p className={styles.question}>{turn.question}</p>
            <Answer
              turn={turn}
              busy={busy}
              onRetry={() =>
                update(turn.id, (current) => ({
                  ...current,
                  state: 'waiting',
                  titleGuess: null,
                  head: '',
                  cards: [],
                  result: null,
                }))
              }
              onExample={ask}
              onOpen={(card) => setOpened(pickBookOf(card))}
              onDirect={(title) =>
                title
                  ? navigate(`/search?title=${encodeURIComponent(title)}`)
                  : navigate('/', { state: { searchTab: 'direct' } satisfies SearchTabState })
              }
            />
            {/* no region or library chosen: the direct search's line, under the latest answer (user 2026-10-24:
                "검색창 아래가 아니라 AI응답 아래에") */}
            {noRegion && index === chat.turns.length - 1 && turn.state !== 'waiting' && turn.state !== 'streaming' && (
              <p className={styles.scopeNote}>
                {SCOPE_TEXT.noteNoneBefore}
                <Link to="/mypage">{SCOPE_TEXT.myPageWord}</Link>
                {SCOPE_TEXT.noteNoneAfter}
              </p>
            )}
          </li>
        ))}
      </ol>
      <div ref={endRef} />
      <PicksBookDialog
        book={opened}
        plan={plan}
        onClose={() => setOpened(null)}
        onSearch={(bookId) => navigate(`/books/${bookId}`)}
        phone={phone}
      />

      {/* laid out after ChatGPT (user 2026-10-24: "크기/비율 반응형을 chatgpt를 참고"): one rounded box with [+] and the
          round send button inside it, the AI notice in small grey under it */}
      <div className={styles.composer}>
        <div className={styles.composerInner}>
        {full ? (
          <div className={styles.full} role="status">
            <p>{AI_TEXT.tooLong}</p>
            <button ref={newChatRef} type="button" className={styles.newChat} onClick={newChat}>
              {AI_TEXT.newChat}
            </button>
          </div>
        ) : (
          <form className={styles.form} onSubmit={handleSubmit} noValidate>
            <label htmlFor="ai-follow-up" className="visually-hidden">
              이어서 묻기
            </label>
            <textarea
              ref={boxRef}
              id="ai-follow-up"
              className={styles.input}
              rows={1}
              value={draft}
              // the box takes no more than the limit, typed or pasted (user 2026-10-24: "제한 글자 수 넘어가면 못 하게")
              maxLength={QUESTION_MAX}
              // no hint once the conversation has started (user 2026-10-24: "이어서 물어보세요는 문구 빼주라")
              placeholder={chat.turns.length === 0 ? AI_TEXT.emptyPlaceholder : undefined}
              autoComplete="off"
              aria-invalid={tooShort ? true : undefined}
              aria-describedby={[tooShort ? 'ai-follow-up-error' : '', nearLimit ? 'ai-follow-up-count' : '']
                .filter(Boolean)
                .join(' ') || undefined}
              onChange={(event) => {
                setDraft(event.target.value)
                setTooShort(false)
                grow(event.target)
              }}
              onKeyDown={handleKeyDown}
            />
            <button
              type="submit"
              className={styles.send}
              aria-label={AI_TEXT.send}
              title={AI_TEXT.send}
              disabled={busy || draft.trim() === ''}
            >
              <svg width="18" height="18" viewBox="0 0 18 18" aria-hidden="true">
                <path
                  d="M9 15V3M4 8l5-5 5 5"
                  fill="none"
                  stroke="currentColor"
                  strokeWidth="2"
                  strokeLinecap="round"
                  strokeLinejoin="round"
                />
              </svg>
            </button>
          </form>
        )}
        {!full && nearLimit && (
          <p id="ai-follow-up-count" className={styles.count}>
            {draft.length}/{QUESTION_MAX}
          </p>
        )}
        {!full && tooShort && (
          <p id="ai-follow-up-error" className={styles.error} role="alert">
            {AI_TEXT.tooShort}
          </p>
        )}
        {/* R9-2's AI line, moved from the top to under the box (user 2026-10-24: "맨 위에 있어서 어색해") */}
        <p className={styles.notice}>{AI_TEXT.notice}</p>
        </div>
      </div>
    </div>
  )
}

type AnswerProps = {
  turn: AiTurnState
  busy: boolean
  onRetry: () => void
  onExample: (question: string) => void
  onOpen: (card: AiTurnCard) => void
  onDirect: (title: string | null) => void
}

function Answer({ turn, busy, onRetry, onExample, onDirect, onOpen }: AnswerProps) {
  const notice = noticeOf(turn)
  return (
    <div className={styles.answer}>
      {turn.titleGuess && (
        <p className={styles.titleGuess}>
          {AI_TEXT.titleGuess(turn.titleGuess)}{' '}
          <button type="button" className={styles.linkButton} onClick={() => onDirect(turn.titleGuess)}>
            {AI_TEXT.titleGuessButton}
          </button>
        </p>
      )}
      {(turn.state === 'waiting' || (turn.state === 'streaming' && !turn.head && turn.cards.length === 0)) && (
        <p className={styles.loading} role="status">
          {AI_TEXT.loading}
        </p>
      )}
      {turn.head && turn.result !== 'no_match' && <p className={styles.head}>{turn.head}</p>}
      {turn.result === 'no_text' && <p className={styles.head}>{AI_TEXT.noText}</p>}
      {turn.cards.length > 0 && (
        <ul className={styles.cards}>
          {turn.cards.map((card) => (
            <li key={card.bookId} className={styles.card}>
              <button
                type="button"
                className={styles.cardLink}
                aria-labelledby={`ai-card-${turn.id}-${card.bookId}`}
                onClick={() => onOpen(card)}
              >
                {card.coverUrl ? (
                  <img className={styles.cover} src={card.coverUrl} alt="" loading="lazy" width="120" height="174" />
                ) : (
                  <span className={styles.cover} aria-hidden="true" />
                )}
                <span className={styles.cardBody}>
                  <span id={`ai-card-${turn.id}-${card.bookId}`} className={styles.cardTitle}>
                    {card.title}
                  </span>
                  {card.author && <span className={styles.cardAuthor}>{card.author}</span>}
                  <span className={styles.cardCount}>{AI_TEXT.myLibraries(card.myLibraryCount)}</span>
                  {card.text && <span className={styles.cardText}>{card.text}</span>}
                </span>
              </button>
            </li>
          ))}
        </ul>
      )}
      {turn.result === 'partial' && <p className={styles.soft}>{AI_TEXT.noText}</p>}
      {turn.result === 'no_match' && (
        <div className={styles.noMatch}>
          <p>{AI_TEXT.noMatch}</p>
          <ul className={styles.examples} aria-label="예시 질문">
            {shownExamples().slice(0, EXAMPLES_SHOWN).map((question) => (
              <li key={question}>
                <button type="button" className={styles.example} onClick={() => onExample(question)}>
                  {question}
                </button>
              </li>
            ))}
          </ul>
        </div>
      )}
      {notice && (
        <div className={styles.alert} role="status">
          <p>{notice.text}</p>
          {notice.action === 'retry' && (
            <button type="button" className={styles.linkButton} onClick={onRetry} disabled={busy}>
              {AI_TEXT.retry}
            </button>
          )}
          {notice.action === 'direct' && (
            <button type="button" className={styles.linkButton} onClick={() => onDirect(null)}>
              {AI_TEXT.toDirect}
            </button>
          )}
        </div>
      )}
    </div>
  )
}

function noticeOf(turn: AiTurnState): { text: string; action?: 'retry' | 'direct' } | null {
  switch (turn.state) {
    case 'too_many':
    case 'busy':
      return { text: AI_TEXT.tooMany }
    case 'paused':
      return { text: AI_TEXT.paused, action: 'direct' }
    case 'connection':
      return { text: AI_TEXT.connection, action: 'retry' }
    case 'failed':
      return { text: AI_TEXT.failed, action: 'retry' }
    default:
      return null
  }
}

function grow(box: HTMLTextAreaElement) {
  box.style.height = 'auto'
  box.style.height = `${box.scrollHeight}px`
}

/** The book window's book for an AI card: our book with the lists' AI summary, no year or video line. */
function pickBookOf(card: AiTurnCard): PickBook {
  return {
    id: String(card.bookId),
    category: '',
    title: card.title,
    listTitle: null,
    coverUrl: card.coverUrl,
    bookId: card.bookId,
    available: card.myLibraryCount > 0,
    recommendedMonth: null,
    yearBook: null,
    videoId: null,
    tocNo: null,
    aiSummary: card.aiSummary ?? null,
  }
}

/** One stream event into the turn. */
export function apply(turn: AiTurnState, event: AiStreamEvent): AiTurnState {
  switch (event.name) {
    case 'title-guess':
      return { ...turn, titleGuess: event.data.title }
    case 'head-delta':
      return { ...turn, head: turn.head + event.data.text }
    case 'card':
      if (turn.cards.some((card) => card.bookId === event.data.bookId)) return turn
      return { ...turn, cards: [...turn.cards, { ...event.data, text: '' }] }
    case 'card-delta':
      return {
        ...turn,
        cards: turn.cards.map((card) =>
          card.bookId === event.data.bookId ? { ...card, text: card.text + event.data.text } : card,
        ),
      }
    case 'done':
      return { ...turn, state: 'done', result: event.data.result }
    case 'error':
      return { ...turn, state: turn.cards.length > 0 ? 'done' : 'failed', result: turn.cards.length > 0 ? 'partial' : null }
  }
}

function refusal(error: unknown): AiTurnState['state'] {
  if (error instanceof ApiError) {
    if (error.status === 429) return 'too_many'
    if (error.status === 503) return error.problem.code === 'ai_search_paused' ? 'paused' : 'busy'
    if (error.status === 404) return 'paused'
    return 'failed'
  }
  return 'connection'
}

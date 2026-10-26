import type { AiAsk, AiCard, AiDoneResult } from '../../api/aiSearch'

/**
 * The conversation of this browser tab (R11-6 [확정]: sessionStorage, kept on reload, gone with the tab; never sent to
 * the server to keep). Each turn keeps its question and what its answer showed.
 */
export type AiAnswerState =
  | 'waiting'
  | 'streaming'
  | 'done'
  | 'too_many'
  | 'paused'
  | 'busy'
  | 'connection'
  | 'failed'

export type AiTurnCard = AiCard & { text: string }

export type AiTurnState = {
  id: string
  question: string
  state: AiAnswerState
  titleGuess: string | null
  head: string
  cards: AiTurnCard[]
  result: AiDoneResult | null
  /** The agent asked back instead of answering (user 2026-10-26); absent in turns kept before it. */
  ask?: AiAsk | null
}

export type AiChat = { turns: AiTurnState[] }

const KEY = 'chaekcheck.aiChat'

export const EMPTY_CHAT: AiChat = { turns: [] }

export function loadChat(storage: Storage | undefined = safeSession()): AiChat {
  try {
    const raw = storage?.getItem(KEY)
    if (!raw) return EMPTY_CHAT
    const parsed = JSON.parse(raw) as AiChat
    // a store of another shape (an older page, a hand edit) starts a new conversation instead of breaking the page
    if (!Array.isArray(parsed.turns) || !parsed.turns.every(isTurn)) return EMPTY_CHAT
    // an answer that was streaming when the page went away is not resumed: it shows the connection notice. A question
    // still waiting (the first screen's) is sent when the chat screen opens.
    return {
      turns: parsed.turns.map((turn) =>
        turn.state === 'streaming'
          ? { ...turn, state: turn.cards.length > 0 ? 'done' : 'connection', result: turn.cards.length > 0 ? 'partial' : null }
          : turn,
      ),
    }
  } catch {
    return EMPTY_CHAT
  }
}

export function saveChat(chat: AiChat, storage: Storage | undefined = safeSession()): void {
  try {
    storage?.setItem(KEY, JSON.stringify(chat))
  } catch {
    // a full or blocked storage keeps the conversation on this page only
  }
}

/** A new question waiting for its answer (the first one comes from the first screen). */
export function addQuestion(chat: AiChat, question: string): AiChat {
  const turn: AiTurnState = {
    id: `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
    question,
    state: 'waiting',
    titleGuess: null,
    head: '',
    cards: [],
    result: null,
    ask: null,
  }
  return { turns: [...chat.turns, turn] }
}

/**
 * The first screen's question always starts a new conversation (user 2026-10-24 "새 대화로 시작"): the earlier one is
 * dropped and not sent as history. Follow-ups are asked in the chat screen's own box.
 */
export function startWithQuestion(question: string, storage: Storage | undefined = safeSession()): void {
  saveChat(addQuestion(EMPTY_CHAT, question), storage)
}

function isTurn(turn: unknown): turn is AiTurnState {
  if (typeof turn !== 'object' || turn === null) return false
  const t = turn as Partial<AiTurnState>
  return (
    typeof t.id === 'string' &&
    typeof t.question === 'string' &&
    typeof t.state === 'string' &&
    typeof t.head === 'string' &&
    Array.isArray(t.cards) &&
    t.cards.every((card) => typeof card?.bookId === 'number' && typeof card.title === 'string' && typeof card.text === 'string')
  )
}

function safeSession(): Storage | undefined {
  try {
    return typeof window === 'undefined' ? undefined : window.sessionStorage
  } catch {
    return undefined
  }
}

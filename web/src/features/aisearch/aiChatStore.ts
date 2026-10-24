import type { AiCard, AiDoneResult } from '../../api/aiSearch'

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
}

export type AiChat = { turns: AiTurnState[] }

const KEY = 'chaekcheck.aiChat'

export const EMPTY_CHAT: AiChat = { turns: [] }

export function loadChat(storage: Storage | undefined = safeSession()): AiChat {
  try {
    const raw = storage?.getItem(KEY)
    if (!raw) return EMPTY_CHAT
    const parsed = JSON.parse(raw) as AiChat
    if (!Array.isArray(parsed.turns)) return EMPTY_CHAT
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
  }
  return { turns: [...chat.turns, turn] }
}

/** The first screen's question: a new conversation unless one is open, then the chat screen opens. */
export function startWithQuestion(question: string, storage: Storage | undefined = safeSession()): void {
  saveChat(addQuestion(loadChat(storage), question), storage)
}

function safeSession(): Storage | undefined {
  try {
    return typeof window === 'undefined' ? undefined : window.sessionStorage
  } catch {
    return undefined
  }
}

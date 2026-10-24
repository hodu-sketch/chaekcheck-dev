import { beforeEach, describe, expect, it } from 'vitest'
import { addQuestion, EMPTY_CHAT, loadChat, saveChat, startWithQuestion } from './aiChatStore'

beforeEach(() => sessionStorage.clear())

describe('aiChatStore (cycle 24 step 10, R11-6)', () => {
  it('keeps a waiting question to send and turns a broken answer into its notice', () => {
    const chat = addQuestion(addQuestion(addQuestion(EMPTY_CHAT, '하나'), '둘'), '셋')
    const card = { no: 1, bookId: 7, title: '코스모스', author: null, coverUrl: null, myLibraryCount: 0, text: '' }
    saveChat({
      turns: [
        { ...chat.turns[0], state: 'streaming' },
        { ...chat.turns[1], state: 'streaming', cards: [card] },
        chat.turns[2],
      ],
    })

    expect(loadChat().turns.map((turn) => [turn.state, turn.result])).toEqual([
      ['connection', null],
      ['done', 'partial'],
      ['waiting', null],
    ])
  })

  it('reads a broken store as an empty chat', () => {
    sessionStorage.setItem('chaekcheck.aiChat', '{not json')
    expect(loadChat()).toEqual(EMPTY_CHAT)
  })

  it('starts a new conversation from a store of another shape', () => {
    sessionStorage.setItem('chaekcheck.aiChat', JSON.stringify({ turns: [{ id: 'a', question: '옛 모양' }] }))
    expect(loadChat()).toEqual(EMPTY_CHAT)
  })

  it('starts a new conversation with a first screen question (user 2026-10-24)', () => {
    saveChat(addQuestion(EMPTY_CHAT, '앞 대화 질문'))
    startWithQuestion('주식 관련 도서')
    expect(loadChat().turns.map((turn) => turn.question)).toEqual(['주식 관련 도서'])
  })
})

import { describe, expect, it } from 'vitest'
import type { ForYouCard } from '../../api/types'
import { pickBook, yearLineOf } from './forYouBook'

function card(over: Partial<ForYouCard>): ForYouCard {
  return {
    bookId: 1,
    title: '코스모스',
    author: null,
    publisher: null,
    coverUrl: null,
    blurb: null,
    tags: [],
    planLibraryCount: 1,
    lists: [],
    authorKey: null,
    ...over,
  }
}

/** S14 opened from 「나에게 맞는 책」 (user 2026-10-24: "24 뒤에 붙임"): the year line and AI summary as on the lists. */
describe('forYouBook', () => {
  it('brings the lists AI summary to the book window', () => {
    expect(pickBook(card({ lists: ['snu100'], aiSummary: '요약' })).aiSummary).toBe('요약')
    expect(pickBook(card({})).aiSummary).toBeNull()
  })

  it('brings the tab blurb when the book has no lists summary, the lists summary first', () => {
    expect(pickBook(card({ blurb: '해요체 요약이에요.' })).aiSummary).toBe('해요체 요약이에요.')
    expect(pickBook(card({ blurb: '소개', aiSummary: '목록 요약' })).aiSummary).toBe('목록 요약')
  })

  it('takes the year line of the first list that has one', () => {
    expect(yearLineOf(card({ lists: ['snu100', 'kaist'], yearBook: 2025 }))).toBe('2025년 KAIST 사서추천')
    expect(yearLineOf(card({ lists: ['librarian', 'kaist'], recommendedMonth: '2025-07', yearBook: 2024 }))).toBe(
      '2025년 7월 국립중앙도서관 사서추천',
    )
  })

  it('has no year line for a book off the lists or on lists without one', () => {
    expect(yearLineOf(card({}))).toBeNull()
    expect(yearLineOf(card({ lists: ['snu100', 'nobel'] }))).toBeNull()
  })
})

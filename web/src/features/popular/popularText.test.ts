import { describe, expect, it } from 'vitest'
import { fetchedLabel, monthLabel, pageCount, safeCoverUrl, sourceLine } from './popularText'

describe('popular texts', () => {
  it('writes the month and the fetch day in Korean time', () => {
    expect(monthLabel('2026-09')).toBe('2026년 9월')
    expect(monthLabel(null)).toBeNull()
    expect(fetchedLabel('2026-10-03T05:01:32+09:00')).toBe('10월 3일 받음')
    // 23:30 UTC on Oct 2 is Oct 3 in Korea
    expect(fetchedLabel('2026-10-02T23:30:00Z')).toBe('10월 3일 받음')
  })

  it('builds the source line (R7-1)', () => {
    expect(sourceLine('2026-09', '2026-10-03T05:01:32+09:00')).toBe(
      '출처: 출판유통통합전산망 (교보문고·예스24·알라딘·영풍문고·지역서점 종이책 판매 합산) · 2026년 9월 · 10월 3일 받음',
    )
  })

  it('counts six pages for fifty books', () => {
    expect(pageCount(50)).toBe(6)
    expect(pageCount(9)).toBe(1)
  })

  it('uses only our own cover addresses (C7)', () => {
    expect(safeCoverUrl('/api/v1/popular-books/covers/812')).toBe('/api/v1/popular-books/covers/812')
    expect(safeCoverUrl('https://example.com/a.jpg')).toBeNull()
    expect(safeCoverUrl('javascript:alert(1)')).toBeNull()
    expect(safeCoverUrl(null)).toBeNull()
  })
})

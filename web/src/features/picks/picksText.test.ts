import { describe, expect, it } from 'vitest'
import { fetchedLabel, monthLabel, pageCount, safeCoverUrl, sourceLine } from './picksText'

describe('picks texts', () => {
  it('writes the month and the fetch day in Korean time', () => {
    expect(monthLabel('2026-09')).toBe('2026년 9월')
    expect(monthLabel(null)).toBeNull()
    expect(fetchedLabel('2026-10-12T05:00:41+09:00')).toBe('10월 12일 받음')
    expect(fetchedLabel('2026-10-03T05:01:32+09:00')).toBe('10월 3일 받음')
    // 23:30 UTC on Oct 2 is Oct 3 in Korea
    expect(fetchedLabel('2026-10-02T23:30:00Z')).toBe('10월 3일 받음')
  })

  it('builds the source line (R7-1)', () => {
    expect(sourceLine('2026-10-12T05:00:41+09:00')).toBe('출처: 국립중앙도서관 사서추천도서 (공공누리 제1유형) · 10월 12일 받음')
    expect(sourceLine(null)).toBe('출처: 국립중앙도서관 사서추천도서 (공공누리 제1유형)')
  })

  it('counts the 9-book pages', () => {
    expect(pageCount(52)).toBe(6)
    expect(pageCount(10)).toBe(2)
    expect(pageCount(9)).toBe(1)
  })

  it('uses only our own cover addresses (C7)', () => {
    expect(safeCoverUrl('/api/v1/picks/covers/812')).toBe('/api/v1/picks/covers/812')
    expect(safeCoverUrl('https://example.com/a.jpg')).toBeNull()
    expect(safeCoverUrl('javascript:alert(1)')).toBeNull()
    expect(safeCoverUrl(null)).toBeNull()
  })
})

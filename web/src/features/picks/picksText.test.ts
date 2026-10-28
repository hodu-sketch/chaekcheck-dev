import { describe, expect, it } from 'vitest'
import { cardTitle, fetchedLabel, monthLabel, safeCoverUrl, sourceLine } from './picksText'

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

  it('shows the main title on a card without a cover', () => {
    expect(cardTitle('시간을 읽는 그림 : 수천 년 세계사를 담은 기록의 그림들')).toBe('시간을 읽는 그림')
    expect(cardTitle('브랜드 코드 : 베타라이프 : 일상에서')).toBe('브랜드 코드')
    expect(cardTitle('Harry Potter = 해리 포터')).toBe('Harry Potter')
    expect(cardTitle('뒷마당 탐조 클럽')).toBe('뒷마당 탐조 클럽')
    expect(cardTitle('사피엔스:유인원에서')).toBe('사피엔스:유인원에서')
  })

  it('uses only our own cover addresses (C7)', () => {
    expect(safeCoverUrl('/api/v1/picks/covers/812')).toBe('/api/v1/picks/covers/812')
    // the other lists' cover files (user 2026-10-20)
    expect(safeCoverUrl('/covers/7f2abb2f74006f006d47b182ecb398a9febfd23d.jpg')).toBe(
      '/covers/7f2abb2f74006f006d47b182ecb398a9febfd23d.jpg',
    )
    expect(safeCoverUrl('/covers/../secrets.jpg')).toBeNull()
    // cycle 26: covers on our CloudFront host, nothing else there
    const key = '7f2abb2f74006f006d47b182ecb398a9febfd23d'
    expect(safeCoverUrl(`https://d1bdzw8sfjph5d.cloudfront.net/covers/${key}.jpg`)).toBe(
      `https://d1bdzw8sfjph5d.cloudfront.net/covers/${key}.jpg`,
    )
    expect(safeCoverUrl(`http://d1bdzw8sfjph5d.cloudfront.net/covers/${key}.jpg`)).toBeNull()
    expect(safeCoverUrl(`https://evil.cloudfront.net/covers/${key}.jpg`)).toBeNull()
    expect(safeCoverUrl(`https://d1bdzw8sfjph5dXcloudfront.net/covers/${key}.jpg`)).toBeNull()
    expect(safeCoverUrl(`https://d1bdzw8sfjph5d.cloudfront.net/covers/${key}.png`)).toBeNull()
    expect(safeCoverUrl('https://d1bdzw8sfjph5d.cloudfront.net/covers/ABC.jpg')).toBeNull()
    expect(safeCoverUrl(`https://d1bdzw8sfjph5d.cloudfront.net/covers/${key}.jpg?x=1`)).toBeNull()
    expect(safeCoverUrl('https://example.com/a.jpg')).toBeNull()
    expect(safeCoverUrl('javascript:alert(1)')).toBeNull()
    expect(safeCoverUrl(null)).toBeNull()
  })
})

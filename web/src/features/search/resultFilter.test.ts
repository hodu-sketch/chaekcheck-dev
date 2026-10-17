import { describe, expect, it } from 'vitest'
import { emptyResultText, readFilter, uncheckedText, writeFilter } from './resultFilter'

describe('result filter in the address', () => {
  it('defaults to available books only', () => {
    expect(readFilter(new URLSearchParams(''))).toBe('available')
    expect(readFilter(new URLSearchParams('filter=available'))).toBe('available')
    expect(readFilter(new URLSearchParams('filter=all'))).toBe('all')
  })

  it('writes only the non-default value', () => {
    expect(writeFilter('all', new URLSearchParams('title=a')).toString()).toBe('title=a&filter=all')
    expect(writeFilter('available', new URLSearchParams('filter=all&title=a')).toString()).toBe('title=a')
  })
})

/** Cycle 20 handoff 1-13: the empty text counts the libraries that answered, the failed ones apart (AC-4, AC-16). */
describe('empty result text with unchecked libraries', () => {
  it('counts only the libraries that answered and appends the failed ones', () => {
    expect(emptyResultText(5, 0, 'available')).toBe('지금까지 5곳에서 지금 빌릴 수 있는 책을 찾지 못했습니다')
    expect(emptyResultText(5, 2, 'available')).toBe('지금까지 3곳에서 지금 빌릴 수 있는 책을 찾지 못했습니다 · 2곳은 확인하지 못했어요')
    expect(emptyResultText(5, 2, 'all')).toBe('지금까지 3곳에서 이 제목을 찾지 못했습니다 · 2곳은 확인하지 못했어요')
  })

  it('says only the failed count when no library answered', () => {
    expect(emptyResultText(2, 2, 'available')).toBe('2곳은 확인하지 못했어요')
    expect(uncheckedText(1)).toBe('1곳은 확인하지 못했어요')
  })
})

import { describe, expect, it } from 'vitest'
import { listsFooter } from './forYouText'

describe('listsFooter (R3-2, AC-3)', () => {
  it('names the tabs, two at most and "외 N" past them', () => {
    expect(listsFooter(['kaist', 'snu100', 'nobel'])).toBe('KAIST·서울대 외 1 추천목록에 있어요')
    expect(listsFooter([])).toBeNull()
  })

  it('never names 이동진 (user 2026-10-25)', () => {
    expect(listsFooter(['leedongjin'])).toBeNull()
    expect(listsFooter(['leedongjin', 'kaist'])).toBe('KAIST 추천목록에 있어요')
  })
})

import { describe, expect, it } from 'vitest'
import type { LendingModel, Vendor } from '../api/types'
import { classificationParts, classificationText } from './libraryClassification'

/** Cycle 09 handoff step 5-2: the words next to a library's name. */
describe('library classification', () => {
  const vendors: Array<[Vendor, string]> = [
    ['KYOBO', '교보'],
    ['YES24', 'YES24'],
    ['BOOKCUBE', '북큐브'],
    ['ALADIN', '알라딘'],
    ['IN_HOUSE', '자체'],
  ]
  const lendings: Array<[LendingModel, string]> = [
    ['OWNED', '소장'],
    ['SUBSCRIPTION', '구독'],
    ['MIXED', '혼합'],
    ['RENTAL', '대여'],
  ]

  it.each(vendors.flatMap(([vendor, v]) => lendings.map(([lendingModel, l]) => [vendor, lendingModel, `${v} · ${l}`] as const)))(
    '%s %s is "%s"',
    (vendor, lendingModel, text) => {
      expect(classificationText({ vendor, lendingModel, orderNo: 1 })).toBe(text)
    },
  )

  it('a portal is "통합 포털" with no lending model', () => {
    expect(classificationText({ vendor: 'PORTAL', lendingModel: null, orderNo: 1 })).toBe('통합 포털')
    // the sixth vendor: a lending model sent anyway is not shown
    for (const [lendingModel] of lendings) expect(classificationText({ vendor: 'PORTAL', lendingModel })).toBe('통합 포털')
  })

  it('a second site of the same library says so (AC-9)', () => {
    expect(classificationText({ vendor: 'YES24', lendingModel: 'OWNED', orderNo: 2 })).toBe('YES24 · 소장 · 2번째 사이트')
    expect(classificationParts({ vendor: 'PORTAL', lendingModel: null, orderNo: 3 })).toEqual(['통합 포털', '3번째 사이트'])
  })

  it('without a vendor there is none (an older server): the name only', () => {
    expect(classificationText({})).toBeNull()
    expect(classificationText({ vendor: null, lendingModel: 'OWNED', orderNo: 2 })).toBeNull()
  })

  it('a non-portal row without its lending model shows the vendor only', () => {
    expect(classificationText({ vendor: 'KYOBO', lendingModel: null })).toBe('교보')
  })
})

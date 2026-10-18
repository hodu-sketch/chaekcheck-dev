import { describe, expect, it } from 'vitest'
import { regionPlaceText } from './regionPlace'

const LABELS = new Map([
  [1, '서울 강남구'],
  [2, '서울 서초구'],
  [3, '경기 성남시'],
  [4, '서울 송파구'],
  [5, '세종특별자치시'],
])

describe('regionPlaceText (①\'s small text)', () => {
  it('names one, two or three regions and shortens four or more', () => {
    expect(regionPlaceText([1], LABELS)).toBe('서울 강남구')
    expect(regionPlaceText([1, 2], LABELS)).toBe('서울 강남구·서초구')
    expect(regionPlaceText([1, 3], LABELS)).toBe('서울 강남구·경기 성남시')
    expect(regionPlaceText([1, 2, 3, 4], LABELS)).toBe('서울 강남구 외 3곳')
    expect(regionPlaceText([5], LABELS)).toBe('세종특별자치시')
  })

  it('is null for none, or while a name is not known yet', () => {
    expect(regionPlaceText([], LABELS)).toBeNull()
    expect(regionPlaceText([1, 99], LABELS)).toBeNull()
  })
})

import { describe, expect, it } from 'vitest'
import type { LendingModel, Vendor } from '../../api/types'
import { findByName } from './libraryNames'

describe('name search', () => {
  const rows = [{ name: '한국형사·법무정책연구원' }, { name: '서울디지털콘텐츠고등학교' }, { name: '신경주대학교' }]

  it('ignores spaces and middle dots (cycle 08: renamed bodies)', () => {
    expect(findByName(rows, '형사법무')).toEqual([rows[0]])
    expect(findByName(rows, '형사·법무')).toEqual([rows[0]])
    expect(findByName(rows, '디지털 콘텐츠')).toEqual([rows[1]])
  })

  it('finds only the new names', () => {
    expect(findByName(rows, '서라벌')).toEqual([])
    expect(findByName(rows, '신경주')).toEqual([rows[2]])
  })
})

/** Cycle 09 handoff step 5-5: every word in the name or in the classification. */
describe('name search with the classification', () => {
  const row = (name: string, vendor?: Vendor, lendingModel?: LendingModel, orderNo = 1) => ({ name, vendor, lendingModel, orderNo })
  const rows = [
    row('동대문구립도서관', 'YES24', 'OWNED'),
    row('동대문구립도서관', 'KYOBO', 'SUBSCRIPTION'),
    row('군산시립도서관', 'YES24', 'OWNED'),
    row('군산시립도서관', 'BOOKCUBE', 'OWNED'),
    row('군산시립도서관', 'KYOBO', 'OWNED'),
    row('창원대학교 거창캠퍼스', 'KYOBO', 'OWNED'),
    row('경기도사이버도서관', 'PORTAL'),
    row('서울교육청 동대문도서관'),
  ]
  const names = (found: typeof rows) => found.map((r) => `${r.name} ${r.vendor ?? '-'}`)

  it('"동대문 yes24" finds only the YES24 row (AC-6)', () => {
    expect(names(findByName(rows, '동대문 yes24'))).toEqual(['동대문구립도서관 YES24'])
  })

  it('lists one name in vendor order (AC-4)', () => {
    expect(names(findByName(rows, '군산시립'))).toEqual(['군산시립도서관 KYOBO', '군산시립도서관 BOOKCUBE', '군산시립도서관 YES24'])
  })

  it('a lending model word narrows too; "교보 · 소장" is one key', () => {
    expect(names(findByName(rows, '동대문 구독'))).toEqual(['동대문구립도서관 KYOBO'])
    expect(names(findByName(rows, '군산 교보·소장'))).toEqual(['군산시립도서관 KYOBO'])
    expect(names(findByName(rows, '포털 경기'))).toEqual(['경기도사이버도서관 PORTAL'])
  })

  it('drops a row when one word matches nothing', () => {
    expect(findByName(rows, '동대문 알라딘')).toEqual([])
    expect(findByName(rows, '군산 서울')).toEqual([])
  })

  it('keeps the whole query (spaces removed) in the name as before', () => {
    expect(names(findByName(rows, '창원대학교거창'))).toEqual(['창원대학교 거창캠퍼스 KYOBO'])
  })

  it('a row without classification matches by its name only', () => {
    expect(names(findByName(rows, '동대문도서관'))).toEqual(['서울교육청 동대문도서관 -'])
    expect(names(findByName(rows, '서울교육청 yes24'))).toEqual([])
  })

  it('names starting with the query come first', () => {
    expect(names(findByName(rows, '동대문'))).toEqual([
      '동대문구립도서관 KYOBO',
      '동대문구립도서관 YES24',
      '서울교육청 동대문도서관 -',
    ])
  })

  it('counts two characters on the whole query', () => {
    expect(findByName(rows, '동')).toEqual([])
    expect(findByName(rows, '동 ')).toEqual([])
  })

  it('puts a second site after the first of the same vendor', () => {
    const two = [row('강남구립도서관', 'YES24', 'OWNED', 2), row('강남구립도서관', 'YES24', 'OWNED', 1)]
    expect(findByName(two, '강남').map((r) => r.orderNo)).toEqual([1, 2])
  })
})

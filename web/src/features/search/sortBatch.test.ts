import { describe, expect, it } from 'vitest'
import type { Eligibility } from '../../api/types'
import { applyFilter, sortBatch, type SortContext } from './sortBatch'
import { testRow } from './testRows'

/** Libraries 1..4 in plan order; library 3 is "가입 자격 확인 필요". */
function context(
  loanRates: Array<[number, number | null]> = [],
  cautioned: number[] = [],
  visitRequired: number[] = [],
): SortContext {
  const eligibility = new Map<number, Eligibility>([
    [1, 'OPEN'],
    [2, 'ELIGIBLE'],
    [3, 'UNVERIFIED'],
    [4, 'OPEN'],
  ])
  return {
    planIndex: new Map([[1, 0], [2, 1], [3, 2], [4, 3]]),
    eligibility,
    cautioned: new Set(cautioned),
    visitRequired: new Set(visitRequired),
    loanRates: new Map(loanRates),
  }
}

const ids = (rows: Array<{ libraryId: number; rank: number }>) => rows.map((r) => `${r.libraryId}/${r.rank}`)

describe('sortBatch (spec 5.6)', () => {
  it('inside a title: joinable without a caution, then with a caution, then "가입 자격 확인 필요" (C2-U-11)', () => {
    // same title at libraries 1 (caution), 3 (확인 필요), 4 (no caution); library 1 has the most stock
    const rows = [
      testRow({ libraryId: 1, rank: 1, titleKey: 't', available: 5, holdings: 5 }),
      testRow({ libraryId: 3, rank: 1, titleKey: 't', available: 9, holdings: 9 }),
      testRow({ libraryId: 4, rank: 1, titleKey: 't', available: 1, holdings: 1 }),
    ]
    expect(ids(sortBatch(rows, context([], [1])).available)).toEqual(['4/1', '1/1', '3/1'])
  })

  it('inside a title: a visit-only library comes after the other joinable ones, before "가입 자격 확인 필요"', () => {
    // library 4 needs a visit and has the most stock; 1 has a caution; 2 is plain; 3 is 확인 필요
    const rows = [
      testRow({ libraryId: 4, rank: 1, titleKey: 't', available: 9, holdings: 9 }),
      testRow({ libraryId: 3, rank: 1, titleKey: 't', available: 8, holdings: 8 }),
      testRow({ libraryId: 1, rank: 1, titleKey: 't', available: 5, holdings: 5 }),
      testRow({ libraryId: 2, rank: 1, titleKey: 't', available: 1, holdings: 1 }),
    ]
    expect(ids(sortBatch(rows, context([], [1], [4])).available)).toEqual(['2/1', '1/1', '4/1', '3/1'])
  })

  it('splits into borrow, reserve (UNKNOWN last) and pending sections', () => {
    const sorted = sortBatch(
      [
        testRow({ libraryId: 1, rank: 1, availability: 'UNKNOWN', availabilityBasis: 'SITE_DETAIL' }),
        testRow({ libraryId: 1, rank: 2, availability: 'RESERVABLE', available: 0 }),
        testRow({ libraryId: 1, rank: 3, availability: 'PENDING', availabilityBasis: 'SITE_DETAIL' }),
        testRow({ libraryId: 1, rank: 4, availability: 'AVAILABLE' }),
      ],
      context(),
    )

    expect(ids(sorted.available)).toEqual(['1/4'])
    expect(ids(sorted.reserve)).toEqual(['1/2', '1/1'])
    expect(ids(sorted.pending)).toEqual(['1/3'])
  })

  it('groups the same title inside a section; more stock first (AC-21)', () => {
    const sorted = sortBatch(
      [
        testRow({ libraryId: 1, rank: 1, titleKey: '사랑', available: 1 }),
        testRow({ libraryId: 2, rank: 1, titleKey: '채식주의자', available: 2 }),
        testRow({ libraryId: 4, rank: 1, titleKey: '사랑', available: 3 }),
      ],
      context(),
    )

    // 사랑: 1 + 3 = 4 in stock, before 채식주의자 (2)
    expect(ids(sorted.available)).toEqual(['4/1', '1/1', '2/1'])
  })

  it('inside a title: joinable first, then stock, loan rate, rank (AC-22)', () => {
    const sorted = sortBatch(
      [
        testRow({ libraryId: 3, rank: 1, titleKey: 'k', available: 9 }),
        testRow({ libraryId: 1, rank: 5, titleKey: 'k', available: 2 }),
        testRow({ libraryId: 2, rank: 1, titleKey: 'k', available: 2 }),
        testRow({ libraryId: 4, rank: 1, titleKey: 'k', available: 5 }),
      ],
      context([
        [1, 0.1],
        [2, 0.5],
      ]),
    )

    expect(ids(sorted.available)).toEqual(['4/1', '1/5', '2/1', '3/1'])
  })

  it('rows without counts come after counted rows of the same title; titles with only such rows go last (AC-16, AC-17)', () => {
    const portal = { holdings: null, available: null, availabilityBasis: 'SITE_LIST' as const }
    const sorted = sortBatch(
      [
        testRow({ libraryId: 2, rank: 1, titleKey: '구독만', ...portal }),
        testRow({ libraryId: 4, rank: 1, titleKey: '섞임', ...portal }),
        testRow({ libraryId: 1, rank: 1, titleKey: '섞임', available: 1 }),
      ],
      context(),
    )

    expect(ids(sorted.available)).toEqual(['1/1', '4/1', '2/1'])
  })

  it('reserve: fewer reservations first, then more holdings', () => {
    const sorted = sortBatch(
      [
        testRow({ libraryId: 1, rank: 1, titleKey: 'a', availability: 'RESERVABLE', reservations: 3, holdings: 1 }),
        testRow({ libraryId: 2, rank: 1, titleKey: 'b', availability: 'RESERVABLE', reservations: 0, holdings: 1 }),
        testRow({ libraryId: 4, rank: 1, titleKey: 'b', availability: 'RESERVABLE', reservations: 0, holdings: 5 }),
      ],
      context(),
    )

    expect(ids(sorted.reserve)).toEqual(['4/1', '2/1', '1/1'])
  })

  it('ties between titles follow the plan order of their first library', () => {
    const sorted = sortBatch(
      [testRow({ libraryId: 4, rank: 1, titleKey: 'x' }), testRow({ libraryId: 2, rank: 1, titleKey: 'y' })],
      context(),
    )

    expect(ids(sorted.available)).toEqual(['2/1', '4/1'])
  })

  it('"available" filter hides the reserve section but keeps rows still being checked', () => {
    const sorted = sortBatch(
      [
        testRow({ libraryId: 1, rank: 1, availability: 'RESERVABLE' }),
        testRow({ libraryId: 1, rank: 2, availability: 'PENDING' }),
        testRow({ libraryId: 1, rank: 3 }),
      ],
      context(),
    )

    const filtered = applyFilter(sorted, 'available')
    expect(filtered.reserve).toEqual([])
    expect(ids(filtered.available)).toEqual(['1/3'])
    expect(ids(filtered.pending)).toEqual(['1/2'])
    expect(applyFilter(sorted, 'all')).toBe(sorted)
  })
})

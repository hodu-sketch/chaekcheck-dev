import { describe, expect, it } from 'vitest'
import type { Eligibility, PriorityGroup, TargetLibrary } from '../../api/types'
import type { BatchView } from './ResultList'
import { GROUP_ORDER, groupBatches, libraryGroup } from './resultGroups'
import { sortBatch, type ResultRow, type SortedBatch } from './sortBatch'
import { testRow } from './testRows'

function library(
  id: number,
  overrides: Partial<Pick<TargetLibrary, 'chosen' | 'visitRequired'>> & {
    priorityGroup?: PriorityGroup
    eligibility?: Eligibility
  } = {},
): TargetLibrary {
  return {
    id,
    name: `library-${id}`,
    libraryType: 'EBOOK',
    platformType: 'KYOBO_V1',
    priorityGroup: 'OPEN',
    deferred: false,
    extraConditionText: null,
    visitRequired: false,
    eligibility: 'OPEN',
    eligibilitySummary: null,
    joinMethod: null,
    joinUrl: null,
    joinCaution: null,
    joinNotice: null,
    homepageUrl: null,
    chosen: false,
    joinSurvey: 'SURVEYED',
    logoUrl: null,
    mobileSiteDiffers: false,
    ...overrides,
  }
}

function view(batchNo: number, sorted: Partial<SortedBatch>): BatchView {
  return {
    batch: { batchNo, kind: 'library', status: 'done', requestedLibraries: [], rows: [] },
    sorted: { available: [], reserve: [], pending: [], unchecked: [], ...sorted },
    answeredSoFar: 0,
  }
}

const ids = (rows: ResultRow[]) => rows.map((r) => `${r.libraryId}/${r.rank}`)
const byId = (...libraries: TargetLibrary[]) => new Map(libraries.map((l) => [l.id, l]))

// 1 my sigungu, 2 my sido, 3 eligible, 4 chosen, 5 open, 6 open + visit, 7 qualified, 8 needs check
const libraries = byId(
  library(1, { priorityGroup: 'MY_SIGUNGU', eligibility: 'ELIGIBLE' }),
  library(2, { priorityGroup: 'MY_SIDO', eligibility: 'ELIGIBLE' }),
  library(3, { priorityGroup: 'QUALIFIED', eligibility: 'ELIGIBLE' }),
  library(4, { chosen: true, priorityGroup: 'QUALIFIED', eligibility: 'UNKNOWN' }),
  library(5, { priorityGroup: 'OPEN', eligibility: 'OPEN' }),
  library(6, { priorityGroup: 'OPEN', eligibility: 'OPEN', visitRequired: true }),
  library(7, { priorityGroup: 'QUALIFIED', eligibility: 'UNKNOWN' }),
  library(8, { priorityGroup: 'NEEDS_CHECK', eligibility: 'UNVERIFIED' }),
)

describe('libraryGroup (D3\')', () => {
  it.each<[string, Parameters<typeof library>[1], string]>([
    ['my sigungu', { priorityGroup: 'MY_SIGUNGU', eligibility: 'UNKNOWN' }, 'MINE'],
    ['my sido', { priorityGroup: 'MY_SIDO', eligibility: 'UNKNOWN' }, 'MINE'],
    ['eligible by my regions', { priorityGroup: 'QUALIFIED', eligibility: 'ELIGIBLE' }, 'MINE'],
    ['chosen, even when OPEN', { chosen: true, priorityGroup: 'OPEN', eligibility: 'OPEN' }, 'MINE'],
    ['chosen, even when not eligible', { chosen: true, priorityGroup: 'NEEDS_CHECK', eligibility: 'UNVERIFIED' }, 'MINE'],
    ['open wherever one lives', { priorityGroup: 'OPEN', eligibility: 'OPEN' }, 'OPEN'],
    ['open with a visit', { priorityGroup: 'OPEN', eligibility: 'OPEN', visitRequired: true }, 'OPEN'],
    ['qualified, unknown', { priorityGroup: 'QUALIFIED', eligibility: 'UNKNOWN' }, 'OTHER'],
    ['needs check, unverified', { priorityGroup: 'NEEDS_CHECK', eligibility: 'UNVERIFIED' }, 'OTHER'],
    ['needs check, unsurveyed', { priorityGroup: 'NEEDS_CHECK', eligibility: 'UNSURVEYED' }, 'OTHER'],
  ])('%s → %s', (_, overrides, expected) => {
    expect(libraryGroup(library(1, overrides))).toBe(expected)
  })

  it('a library not in the plan → OTHER', () => {
    expect(libraryGroup(undefined)).toBe('OTHER')
  })
})

describe('groupBatches (D3, R5-3)', () => {
  it('always returns the four groups in order MINE, OPEN, OTHER, UNCHECKED, empty ones included', () => {
    const { groups, visitRequiredOpenSites } = groupBatches([], libraries)
    expect(groups.map((g) => g.key)).toEqual(GROUP_ORDER)
    expect(groups.map((g) => g.count)).toEqual([0, 0, 0, 0])
    expect(visitRequiredOpenSites).toBe(0)
  })

  it('puts each row in its library\'s group and keeps the subsection order of the batch', () => {
    const { groups } = groupBatches(
      [
        view(1, {
          available: [
            testRow({ libraryId: 5, rank: 1 }),
            testRow({ libraryId: 1, rank: 1 }),
            testRow({ libraryId: 7, rank: 1 }),
            testRow({ libraryId: 4, rank: 1 }),
          ],
          reserve: [testRow({ libraryId: 2, rank: 2, availability: 'RESERVABLE' })],
          pending: [testRow({ libraryId: 8, rank: 3, availability: 'PENDING' })],
        }),
      ],
      libraries,
    )
    const [mine, open, other, unchecked] = groups
    expect(ids(mine.available)).toEqual(['1/1', '4/1'])
    expect(ids(mine.reserve)).toEqual(['2/2'])
    expect(ids(open.available)).toEqual(['5/1'])
    expect(ids(other.available)).toEqual(['7/1'])
    expect(ids(other.pending)).toEqual(['8/3'])
    expect(unchecked.count).toBe(0)
  })

  it('a later batch\'s rows go to the end of their own subsection, not the end of the group', () => {
    const { groups } = groupBatches(
      [
        view(1, {
          available: [testRow({ libraryId: 1, rank: 1 })],
          reserve: [testRow({ libraryId: 1, rank: 2, availability: 'RESERVABLE' })],
          pending: [testRow({ libraryId: 2, rank: 3, availability: 'PENDING' })],
        }),
        view(2, {
          available: [testRow({ libraryId: 3, rank: 1 }), testRow({ libraryId: 5, rank: 1 })],
          reserve: [testRow({ libraryId: 2, rank: 2, availability: 'RESERVABLE' })],
        }),
      ],
      libraries,
    )
    const mine = groups[0]
    expect(ids(mine.available)).toEqual(['1/1', '3/1'])
    expect(ids(mine.reserve)).toEqual(['1/2', '2/2'])
    expect(ids(mine.pending)).toEqual(['2/3'])
    // the OPEN row of batch 2 stays in OPEN
    expect(ids(groups[1].available)).toEqual(['5/1'])
  })

  it('failed and UNKNOWN rows go to UNCHECKED whatever their library\'s group (sortBatch.unchecked)', () => {
    const context = {
      planIndex: new Map([[1, 0], [5, 1]]),
      eligibility: new Map<number, Eligibility>([[1, 'ELIGIBLE'], [5, 'OPEN']]),
      cautioned: new Set<number>(),
      visitRequired: new Set<number>(),
      loanRates: new Map<number, number | null>(),
      chosen: new Set<number>(),
    }
    const first = sortBatch(
      [
        testRow({ libraryId: 1, rank: 1, availability: 'UNKNOWN', availabilityBasis: 'SITE_DETAIL' }),
        testRow({ libraryId: 1, rank: 2 }),
      ],
      context,
    )
    const second = sortBatch([testRow({ libraryId: 5, rank: 1, failed: true, availability: 'UNKNOWN' })], context)
    const { groups } = groupBatches([view(1, first), view(2, second)], libraries)
    const [mine, open, , unchecked] = groups
    expect(ids(mine.available)).toEqual(['1/2'])
    expect(ids(unchecked.unchecked)).toEqual(['1/1', '5/1'])
    expect(unchecked.count).toBe(2)
    expect(open.count).toBe(0)
  })

  it('counts rows per group and per subsection', () => {
    const { groups } = groupBatches(
      [
        view(1, {
          available: [testRow({ libraryId: 1, rank: 1 }), testRow({ libraryId: 5, rank: 1 })],
          reserve: [testRow({ libraryId: 5, rank: 2, availability: 'RESERVABLE' })],
          pending: [testRow({ libraryId: 6, rank: 3, availability: 'PENDING' })],
        }),
        view(2, {
          available: [testRow({ libraryId: 6, rank: 1 })],
          unchecked: [testRow({ libraryId: 7, rank: 1, failed: true })],
        }),
      ],
      libraries,
    )
    const [mine, open, other, unchecked] = groups
    expect(mine.count).toBe(1)
    expect([open.available.length, open.reserve.length, open.pending.length]).toEqual([2, 1, 1])
    expect(open.count).toBe(4)
    expect(other.count).toBe(0)
    expect(unchecked.count).toBe(1)
  })

  it('counts the distinct visit-required sites among the OPEN rows shown now (R8-2)', () => {
    const visitLibraries = byId(
      ...libraries.values(),
      library(9, { priorityGroup: 'OPEN', eligibility: 'OPEN', visitRequired: true }),
      // visit-required but MINE (chosen) or OTHER: not counted
      library(10, { chosen: true, eligibility: 'OPEN', visitRequired: true }),
      library(11, { priorityGroup: 'QUALIFIED', eligibility: 'UNKNOWN', visitRequired: true }),
      // visit-required OPEN, but its only row could not be checked: not counted
      library(12, { priorityGroup: 'OPEN', eligibility: 'OPEN', visitRequired: true }),
    )
    const { visitRequiredOpenSites } = groupBatches(
      [
        view(1, {
          available: [
            testRow({ libraryId: 6, rank: 1 }),
            testRow({ libraryId: 6, rank: 2 }),
            testRow({ libraryId: 5, rank: 1 }),
            testRow({ libraryId: 10, rank: 1 }),
            testRow({ libraryId: 11, rank: 1 }),
          ],
          unchecked: [testRow({ libraryId: 12, rank: 1, failed: true })],
        }),
        view(2, { pending: [testRow({ libraryId: 9, rank: 1, availability: 'PENDING' })] }),
      ],
      visitLibraries,
    )
    expect(visitRequiredOpenSites).toBe(2)
  })

  it('a visit-required OPEN library whose rows the filter hid is not counted', () => {
    // "지금 빌릴 수 있는 책만": applyFilter left reserve empty, so library 6's reserve row is not shown
    const { visitRequiredOpenSites, groups } = groupBatches(
      [view(1, { available: [testRow({ libraryId: 5, rank: 1 })], reserve: [] })],
      libraries,
    )
    expect(visitRequiredOpenSites).toBe(0)
    expect(groups[1].count).toBe(1)
  })
})

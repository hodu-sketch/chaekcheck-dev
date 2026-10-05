import { afterEach, describe, expect, it } from 'vitest'
import {
  answeredAccounts,
  libraryImportNote,
  partsToOffer,
  planImport,
  REGION_IMPORT_KEY,
  rememberAnswer,
} from './regionImport'

afterEach(() => localStorage.clear())

const EMPTY = { residence: [], work: [] }
const BROWSER = { residence: [1114], work: [1102] }

describe('S11 conditions (AC-3; cycle 07 AC-19)', () => {
  const base = {
    accountId: 7,
    serverRegions: EMPTY,
    serverLibraries: [] as number[],
    browserRegions: BROWSER,
    browserLibraries: [281],
    answered: [] as number[],
  }

  it('offers each part only when the account has none of it and this browser has some', () => {
    expect(partsToOffer(base)).toEqual({ regions: true, libraries: true })
    expect(partsToOffer({ ...base, serverRegions: { residence: [1114], work: [] } })).toEqual({
      regions: false,
      libraries: true,
    })
    expect(partsToOffer({ ...base, serverLibraries: [5] })).toEqual({ regions: true, libraries: false })
    expect(partsToOffer({ ...base, browserLibraries: [] })).toEqual({ regions: true, libraries: false })
    expect(partsToOffer({ ...base, browserRegions: EMPTY, serverLibraries: [5] })).toBeNull()
  })

  it('needs an account, its values read and no answer yet', () => {
    expect(partsToOffer({ ...base, accountId: null })).toBeNull()
    expect(partsToOffer({ ...base, serverRegions: null })).toBeNull()
    expect(partsToOffer({ ...base, serverLibraries: null })).toBeNull()
    expect(partsToOffer({ ...base, answered: [7] })).toBeNull()
    expect(partsToOffer({ ...base, answered: [8] })).not.toBeNull()
  })

  it('says when fewer libraries were saved than sent', () => {
    expect(libraryImportNote(3, 3, 20)).toBeNull()
    expect(libraryImportNote(21, 20, 20)).toBe('20곳까지만 저장했어요')
    expect(libraryImportNote(3, 2, 20)).toBe('지금 쓸 수 없는 도서관 1곳은 빼고 저장했어요')
  })

  it('keeps every account that answered, once each', () => {
    rememberAnswer(7)
    rememberAnswer(9)
    rememberAnswer(7)
    expect(answeredAccounts()).toEqual([7, 9])
    expect(JSON.parse(localStorage.getItem(REGION_IMPORT_KEY)!)).toEqual([7, 9])
  })

  it('ignores a broken stored value', () => {
    localStorage.setItem(REGION_IMPORT_KEY, '{oops')
    expect(answeredAccounts()).toEqual([])
    localStorage.setItem(REGION_IMPORT_KEY, '[7,"x",-1]')
    expect(answeredAccounts()).toEqual([7])
  })
})

describe('what S11 saves', () => {
  it('swaps replaced regions and leaves split and unknown ones out', () => {
    const plan = planImport({ residence: [9001, 1114, 9002], work: [9003, 9001] }, [
      { id: 1114, status: 'ACTIVE', label: '서울 마포구', replacements: [] },
      { id: 9001, status: 'REPLACED', label: '시험 폐지구', replacements: [{ id: 1102, label: '서울 중구' }] },
      { id: 9002, status: 'SPLIT', label: '시험 분할구', replacements: [] },
      { id: 9003, status: 'UNKNOWN', label: null, replacements: [] },
    ])

    expect(plan.regions).toEqual({ residence: [1102, 1114], work: [1102] })
    expect(plan.replacedTo).toEqual(['서울 중구'])
    expect(plan.dropped).toEqual(['시험 분할구', null])
  })
})

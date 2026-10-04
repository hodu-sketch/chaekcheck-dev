import { afterEach, describe, expect, it } from 'vitest'
import { answeredAccounts, planImport, REGION_IMPORT_KEY, rememberAnswer, shouldOfferImport } from './regionImport'

afterEach(() => localStorage.clear())

const EMPTY = { residence: [], work: [] }
const BROWSER = { residence: [1114], work: [1102] }

describe('S11 conditions (AC-3)', () => {
  it('needs an account, empty saved regions, regions in this browser and no answer yet', () => {
    const base = { accountId: 7, serverRegions: EMPTY, browserRegions: BROWSER, answered: [] }
    expect(shouldOfferImport(base)).toBe(true)
    expect(shouldOfferImport({ ...base, accountId: null })).toBe(false)
    expect(shouldOfferImport({ ...base, serverRegions: null })).toBe(false)
    expect(shouldOfferImport({ ...base, serverRegions: { residence: [1114], work: [] } })).toBe(false)
    expect(shouldOfferImport({ ...base, browserRegions: EMPTY })).toBe(false)
    expect(shouldOfferImport({ ...base, answered: [7] })).toBe(false)
    expect(shouldOfferImport({ ...base, answered: [8] })).toBe(true)
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

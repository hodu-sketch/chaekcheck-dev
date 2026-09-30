import { describe, expect, it } from 'vitest'
import type { RegionResolution } from '../../api/types'
import { applyResolutions } from './applyResolutions'
import { loadRegions, REGION_STORAGE_KEY, saveRegions } from './regionStorage'

const active = (id: number, label: string): RegionResolution => ({ id, status: 'ACTIVE', label, replacements: [] })

describe('applyResolutions (spec 5.8, AC-20)', () => {
  it('replaces an id linked to one new region and says so', () => {
    const outcome = applyResolutions({ residence: [9001, 1114], work: [] }, [
      { id: 9001, status: 'REPLACED', label: '테스트 대체구', replacements: [{ id: 1116, label: '서울 강서구' }] },
      active(1114, '서울 마포구'),
    ])
    expect(outcome.regions).toEqual({ residence: [1116, 1114], work: [] })
    expect(outcome.changed).toBe(true)
    expect(outcome.notices).toEqual([
      { kind: 'replaced', message: '행정구역 변경으로 테스트 대체구가 서울 강서구로 바뀌었습니다' },
    ])
  })

  it('drops a split region and asks to choose again', () => {
    const outcome = applyResolutions({ residence: [], work: [9002] }, [
      {
        id: 9002,
        status: 'SPLIT',
        label: '테스트 분할군',
        replacements: [
          { id: 1114, label: '서울 마포구' },
          { id: 1116, label: '서울 강서구' },
        ],
      },
    ])
    expect(outcome.regions.work).toEqual([])
    expect(outcome.notices).toEqual([
      { kind: 'split', message: '테스트 분할군이 여러 지역으로 나뉘었습니다. 내 지역을 다시 골라 주세요' },
    ])
  })

  it('drops unknown ids quietly and keeps active ones', () => {
    const outcome = applyResolutions({ residence: [12345, 1114], work: [] }, [
      { id: 12345, status: 'UNKNOWN', label: null, replacements: [] },
      active(1114, '서울 마포구'),
    ])
    expect(outcome.regions.residence).toEqual([1114])
    expect(outcome.notices).toEqual([])
    expect(outcome.changed).toBe(true)
  })

  it('reports no change when everything is active', () => {
    const outcome = applyResolutions({ residence: [1114], work: [1102] }, [active(1114, 'a'), active(1102, 'b')])
    expect(outcome.changed).toBe(false)
  })

  it('does not duplicate when the replacement is already chosen', () => {
    const outcome = applyResolutions({ residence: [9001, 1116], work: [] }, [
      { id: 9001, status: 'REPLACED', label: '테스트 대체구', replacements: [{ id: 1116, label: '서울 강서구' }] },
      active(1116, '서울 강서구'),
    ])
    expect(outcome.regions.residence).toEqual([1116])
  })
})

describe('regionStorage (AC-6)', () => {
  it('round-trips and ignores broken data', () => {
    saveRegions({ residence: [1114], work: [1102] })
    expect(loadRegions()).toEqual({ residence: [1114], work: [1102] })

    localStorage.setItem(REGION_STORAGE_KEY, '{"residence":["x",-1,1114,1114],"work":"nope"}')
    expect(loadRegions()).toEqual({ residence: [1114], work: [] })

    localStorage.setItem(REGION_STORAGE_KEY, 'not json')
    expect(loadRegions()).toEqual({ residence: [], work: [] })
  })
})

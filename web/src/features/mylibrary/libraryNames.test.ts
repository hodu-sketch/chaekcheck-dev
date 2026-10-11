import { describe, expect, it } from 'vitest'
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

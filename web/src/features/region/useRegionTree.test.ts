import { describe, expect, it } from 'vitest'
import { shownRegionTree } from './useRegionTree'

describe('shownRegionTree', () => {
  it('shows 전남광주 as 전남 and lists each SIDO\'s SIGUNGU in 가나다 order', () => {
    const shown = shownRegionTree([
      {
        id: 46,
        name: '전남광주통합특별시',
        shortName: '전남광주',
        children: [
          { id: 2, name: '해남군' },
          { id: 1, name: '광산구' },
          { id: 3, name: '나주시' },
        ],
      },
      { id: 11, name: '서울특별시', shortName: '서울', children: [{ id: 5, name: '종로구' }, { id: 4, name: '강서구' }] },
    ] as never)
    expect(shown.map((sido) => sido.shortName)).toEqual(['전남', '서울'])
    expect(shown[0].children.map((c) => c.name)).toEqual(['광산구', '나주시', '해남군'])
    expect(shown[1].children.map((c) => c.name)).toEqual(['강서구', '종로구'])
  })
})

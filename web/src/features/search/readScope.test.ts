import { describe, expect, it } from 'vitest'
import { readScope } from './readScope'

describe('readScope (cycle 22 R4-1, D10)', () => {
  const read = (query: string) => readScope(new URLSearchParams(query))

  it('is all only with scope=all in the address', () => {
    expect(read('title=사랑&scope=all')).toBe('all')
  })

  it('is joinable otherwise, whatever this browser kept before', () => {
    localStorage.setItem('chaekcheck.searchSettings.v1', JSON.stringify({ guestScope: 'all', myScope: 'all' }))
    expect(read('')).toBe('joinable')
    expect(read('scope=joinable')).toBe('joinable')
    expect(read('scope=something')).toBe('joinable')
    localStorage.clear()
  })
})

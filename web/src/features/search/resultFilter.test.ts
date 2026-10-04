import { describe, expect, it } from 'vitest'
import { readFilter, writeFilter } from './resultFilter'

describe('result filter in the address', () => {
  it('defaults to available books only', () => {
    expect(readFilter(new URLSearchParams(''))).toBe('available')
    expect(readFilter(new URLSearchParams('filter=available'))).toBe('available')
    expect(readFilter(new URLSearchParams('filter=all'))).toBe('all')
  })

  it('writes only the non-default value', () => {
    expect(writeFilter('all', new URLSearchParams('title=a')).toString()).toBe('title=a&filter=all')
    expect(writeFilter('available', new URLSearchParams('filter=all&title=a')).toString()).toBe('title=a')
  })
})

import { describe, expect, it } from 'vitest'
import { direction, subject } from './josa'
import { checkQuery, normalizeQuery } from './query'

describe('query (AC-1)', () => {
  it('normalizes like the server', () => {
    expect(normalizeQuery('  채식\t\n 주의자 ')).toBe('채식 주의자')
  })

  it('needs 2 to 50 characters', () => {
    expect(checkQuery('가')).toBe('too_short')
    expect(checkQuery('')).toBe('too_short')
    expect(checkQuery('사랑')).toBeNull()
    expect(checkQuery('가'.repeat(50))).toBeNull()
    expect(checkQuery('가'.repeat(51))).toBe('too_long')
  })
})

describe('josa', () => {
  it('picks 이/가 and 으로/로 by the final consonant', () => {
    expect(subject('서울 마포구')).toBe('서울 마포구가')
    expect(subject('고흥군')).toBe('고흥군이')
    expect(direction('서울 강서구')).toBe('서울 강서구로')
    expect(direction('고흥군')).toBe('고흥군으로')
    expect(direction('서울')).toBe('서울로')
  })
})

import { cleanup, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it } from 'vitest'
import { AboutPage } from './AboutPage'

afterEach(cleanup)

describe('S12 서비스 안내 (cycle 07, ADR-0059)', () => {
  it('says whose logos and names these are', () => {
    render(<AboutPage />)

    expect(screen.getByRole('heading', { name: '서비스 안내' })).toBeTruthy()
    expect(screen.getByText('각 도서관의 로고와 이름은 그 기관의 것이며 책첵은 제휴·후원 관계가 없습니다.')).toBeTruthy()
  })
})

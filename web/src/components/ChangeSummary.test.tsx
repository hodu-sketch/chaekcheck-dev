import { cleanup, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it } from 'vitest'
import { ChangeSummary } from './ChangeSummary'

afterEach(cleanup)

describe('ChangeSummary (cycle 16, AC-11)', () => {
  it('is one folded line that opens in place', async () => {
    render(<ChangeSummary summary={'- **보유 기간**을 1년으로 바꿨어요'} />)

    const toggle = screen.getByRole('button', { name: '바뀐 점 보기' })
    expect(toggle.getAttribute('aria-expanded')).toBe('false')
    expect(screen.queryByText('보유 기간')).toBeNull()

    await userEvent.setup().click(toggle)

    expect(toggle.getAttribute('aria-expanded')).toBe('true')
    expect(screen.getByText('보유 기간').tagName).toBe('STRONG')

    await userEvent.setup().click(toggle)
    expect(toggle.getAttribute('aria-expanded')).toBe('false')
  })

  it('is not there without a change summary', () => {
    render(<ChangeSummary summary={null} />)

    expect(screen.queryByRole('button')).toBeNull()
  })
})

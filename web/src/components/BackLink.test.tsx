import { cleanup, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Link, MemoryRouter, Route, Routes } from 'react-router'
import { afterEach, beforeAll, describe, expect, it } from 'vitest'
import { BackLink } from './BackLink'
import { InfoTip } from './InfoTip'

afterEach(cleanup)

beforeAll(() => {
  globalThis.ResizeObserver ??= class {
    observe() {}
    unobserve() {}
    disconnect() {}
  }
})

function App({ start }: { start: string }) {
  return (
    <MemoryRouter initialEntries={[start]}>
      <Routes>
        <Route path="/" element={<p>첫 화면</p>} />
        <Route path="/search" element={<Link to="/terms">약관</Link>} />
        <Route path="/terms" element={<BackLink />} />
      </Routes>
    </MemoryRouter>
  )
}

/** Cycle 08 (flow.md 검토 #7): "← 돌아가기" goes to the previous screen, or to S1 when there is none. */
describe('back link', () => {
  it('goes back to the screen it came from', async () => {
    const user = userEvent.setup()
    render(<App start="/search" />)
    await user.click(screen.getByRole('link', { name: '약관' }))
    await user.click(screen.getByRole('button', { name: '돌아가기' }))
    expect(screen.getByRole('link', { name: '약관' })).toBeTruthy()
  })

  it('goes to the start screen when the page was opened from its address', async () => {
    render(<App start="/terms" />)
    await userEvent.setup().click(screen.getByRole('button', { name: '돌아가기' }))
    expect(screen.getByText('첫 화면')).toBeTruthy()
  })
})

describe('info tip', () => {
  it('shows its text on a tap', async () => {
    render(<InfoTip label="저장 안내" text="고르는 즉시 이 브라우저에 저장돼요" />)
    await userEvent.setup().click(screen.getByRole('button', { name: '저장 안내' }))
    expect(screen.getByText('고르는 즉시 이 브라우저에 저장돼요')).toBeTruthy()
  })
})

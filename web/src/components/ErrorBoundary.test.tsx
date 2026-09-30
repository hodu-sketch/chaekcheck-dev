import { cleanup, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ErrorBoundary } from './ErrorBoundary'
import { ErrorFallback } from './ErrorFallback'

afterEach(cleanup)

let shouldThrow = true

function Flaky() {
  if (shouldThrow) throw new Error('boom')
  return <p>화면 정상</p>
}

function Harness() {
  const [address, setAddress] = useState('/search?title=ab')
  return (
    <>
      <button type="button" onClick={() => setAddress('/search?title=cd')}>
        새 검색
      </button>
      <ErrorBoundary resetKey={address} fallback={(reset) => <ErrorFallback onRetry={reset} />}>
        <Flaky />
      </ErrorBoundary>
    </>
  )
}

describe('ErrorBoundary', () => {
  it('shows the fallback instead of a blank page, and 다시 시도 renders the screen again', async () => {
    vi.spyOn(console, 'error').mockImplementation(() => {})
    shouldThrow = true
    const user = userEvent.setup()
    render(<Harness />)

    expect(screen.getByRole('alert').textContent).toContain('문제가 생겼어요')

    shouldThrow = false
    await user.click(screen.getByRole('button', { name: '다시 시도' }))
    expect(screen.getByText('화면 정상')).toBeTruthy()
  })

  it('renders again when the address changes', async () => {
    vi.spyOn(console, 'error').mockImplementation(() => {})
    shouldThrow = true
    const user = userEvent.setup()
    render(<Harness />)
    expect(screen.getByRole('alert')).toBeTruthy()

    shouldThrow = false
    await user.click(screen.getByRole('button', { name: '새 검색' }))
    expect(screen.getByText('화면 정상')).toBeTruthy()
  })
})

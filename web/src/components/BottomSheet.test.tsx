import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { afterEach, describe, expect, it } from 'vitest'
import { BottomSheet } from './BottomSheet'

afterEach(cleanup)

function Harness({ closeButton = false, closeOnBack = false }: { closeButton?: boolean; closeOnBack?: boolean }) {
  const [open, setOpen] = useState(false)
  return (
    <>
      <button type="button" onClick={() => setOpen(true)}>
        열기
      </button>
      <BottomSheet open={open} onOpenChange={setOpen} title="시험 창" closeButton={closeButton} closeOnBack={closeOnBack}>
        <p>내용</p>
      </BottomSheet>
    </>
  )
}

function pull(handle: HTMLElement, from: number, to: number) {
  fireEvent.pointerDown(handle, { clientY: from, pointerId: 1 })
  fireEvent.pointerMove(handle, { clientY: to, pointerId: 1 })
  fireEvent.pointerUp(handle, { clientY: to, pointerId: 1 })
}

/** Cycle 10 step 6 (C6, AC-18): the shared phone sheet, opened without a trigger of its own. */
describe('bottom sheet', () => {
  it('closes when the handle is pulled down more than 80px, not less', async () => {
    render(<Harness />)
    await userEvent.setup().click(screen.getByRole('button', { name: '열기' }))
    const handle = screen.getByTestId('sheet-handle')
    pull(handle, 500, 580)
    expect(screen.getByRole('dialog', { name: '시험 창' })).toBeTruthy()
    pull(handle, 500, 581)
    expect(screen.queryByRole('dialog')).toBeNull()
  })

  it('closes on a tap outside', async () => {
    render(<Harness />)
    const user = userEvent.setup()
    await user.click(screen.getByRole('button', { name: '열기' }))
    expect(screen.getByRole('dialog')).toBeTruthy()
    // Radix closes a modal dialog on a pointer down outside its content (listening from the next tick)
    await new Promise((resolve) => setTimeout(resolve, 0))
    const overlay = document.querySelector('.sheetOverlay')!
    fireEvent.pointerDown(overlay)
    fireEvent.click(overlay)
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
  })

  it('has a ⓧ that closes it when asked for', async () => {
    render(<Harness closeButton />)
    const user = userEvent.setup()
    await user.click(screen.getByRole('button', { name: '열기' }))
    await user.click(screen.getByRole('button', { name: '닫기' }))
    expect(screen.queryByRole('dialog')).toBeNull()
  })

  it('has no ⓧ otherwise', async () => {
    render(<Harness />)
    await userEvent.setup().click(screen.getByRole('button', { name: '열기' }))
    expect(screen.getByRole('dialog')).toBeTruthy()
    expect(screen.queryByRole('button', { name: '닫기' })).toBeNull()
  })

  it('back closes only the sheet; closing it otherwise takes its history entry away', async () => {
    render(<Harness closeButton closeOnBack />)
    const user = userEvent.setup()
    const before = window.history.length
    await user.click(screen.getByRole('button', { name: '열기' }))
    await waitFor(() => expect(window.history.length).toBe(before + 1))

    window.history.back()
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())

    await user.click(screen.getByRole('button', { name: '열기' }))
    await waitFor(() => expect(window.history.state).toHaveProperty('chaekcheckSheet'))
    await user.click(screen.getByRole('button', { name: '닫기' }))
    await waitFor(() => expect(window.history.state?.chaekcheckSheet).toBeUndefined())
    expect(screen.queryByRole('dialog')).toBeNull()
  })
})

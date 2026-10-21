import { cleanup, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes, useNavigate } from 'react-router'
import { afterEach, describe, expect, it } from 'vitest'
import { NoticeBar, NoticeProvider, useNotice } from './NoticeContext'

afterEach(cleanup)

/** Shows a notice on the way from /signup to /, like the sign-up screen does, and moves between pages. */
function Controls() {
  const { showNotice } = useNotice()
  const navigate = useNavigate()
  return (
    <>
      <button
        type="button"
        onClick={() => {
          showNotice('가입했어요')
          navigate('/', { replace: true })
        }}
      >
        가입
      </button>
      <button type="button" onClick={() => showNotice('저장하지 않은 지역이 있어요')}>
        여기서 안내
      </button>
      <button type="button" onClick={() => navigate('/mypage')}>
        다른 화면
      </button>
      <button type="button" onClick={() => navigate('/?title=사랑')}>
        같은 화면 주소만
      </button>
    </>
  )
}

function renderBar(start: string) {
  render(
    <NoticeProvider>
      <MemoryRouter initialEntries={[start]}>
        <NoticeBar />
        <Routes>
          <Route path="*" element={<Controls />} />
        </Routes>
      </MemoryRouter>
    </NoticeProvider>,
  )
}

describe('NoticeBar', () => {
  it('keeps the notice on the page it lands on and drops it on the next page (user request 2026-10-05)', async () => {
    renderBar('/signup')
    const user = userEvent.setup()

    await user.click(screen.getByRole('button', { name: '가입' }))
    expect(screen.getByRole('status').textContent).toContain('가입했어요')

    // the same page with another address still shows it
    await user.click(screen.getByRole('button', { name: '같은 화면 주소만' }))
    expect(screen.getByRole('status').textContent).toContain('가입했어요')

    await user.click(screen.getByRole('button', { name: '다른 화면' }))
    expect(screen.queryByRole('status')).toBeNull()
  })

  it('a notice shown in place goes away on leaving, and a later one is shown again', async () => {
    renderBar('/')
    const user = userEvent.setup()

    await user.click(screen.getByRole('button', { name: '여기서 안내' }))
    expect(screen.getByRole('status')).toBeTruthy()
    await user.click(screen.getByRole('button', { name: '다른 화면' }))
    expect(screen.queryByRole('status')).toBeNull()

    await user.click(screen.getByRole('button', { name: '여기서 안내' }))
    expect(screen.getByRole('status').textContent).toContain('저장하지 않은 지역이 있어요')
  })
})

import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../../api/client'
import { RegionModal } from './RegionModal'

/** S2 saving to the account (cycle 06 step 9): states of 저장. */

const TREE = [{ id: 11, name: '서울특별시', shortName: '서울', children: [{ id: 1114, name: '마포구' }, { id: 1102, name: '중구' }] }]

beforeEach(() => {
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string) => {
      if (url === '/api/v1/regions') return new Response(JSON.stringify(TREE), { status: 200 })
      if (url.startsWith('/api/v1/regions/resolutions')) {
        return new Response(
          JSON.stringify([{ id: 9001, status: 'REPLACED', label: '시험 폐지구', replacements: [{ id: 1102, label: '서울 중구' }] }]),
          { status: 200 },
        )
      }
      throw new Error(`unexpected ${url}`)
    }),
  )
})

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

function renderModal(onSave: () => Promise<void>, regions = { residence: [1114], work: [] }) {
  const onOpenChange = vi.fn()
  render(
    <QueryClientProvider client={new QueryClient()}>
      <RegionModal open onOpenChange={onOpenChange} regions={regions} onSave={onSave} savedTo="account" />
    </QueryClientProvider>,
  )
  return onOpenChange
}

const problem = (status: number, code: string) => new ApiError(status, { title: 'x', status, code })

describe('S2 save to the account', () => {
  it('says where it saves and is disabled while saving', async () => {
    let finish: () => void = () => {}
    renderModal(() => new Promise<void>((resolve) => (finish = resolve)))
    expect(screen.getByText(/계정에 저장돼요/)).toBeTruthy()

    await userEvent.setup().click(screen.getByRole('button', { name: '저장' }))

    const button = screen.getByRole('button', { name: '저장 중' }) as HTMLButtonElement
    expect(button.disabled).toBe(true)
    finish()
    await waitFor(() => expect(screen.getByRole('button', { name: '저장' })).toBeTruthy())
  })

  it('a failed save keeps the modal and the draft with the message', async () => {
    renderModal(() => Promise.reject(problem(500, 'internal_error')))

    await userEvent.setup().click(screen.getByRole('button', { name: '저장' }))

    expect((await screen.findByRole('alert')).textContent).toBe('저장하지 못했어요. 잠시 뒤 다시 해 주세요')
    expect(screen.getByRole('dialog')).toBeTruthy()
  })

  it('401: says the login is gone', async () => {
    renderModal(() => Promise.reject(problem(401, 'unauthorized')))

    await userEvent.setup().click(screen.getByRole('button', { name: '저장' }))

    expect((await screen.findByRole('alert')).textContent).toContain('로그인이 풀렸어요')
  })

  it('422: resolves the draft and asks to check again', async () => {
    renderModal(() => Promise.reject(problem(422, 'invalid_region')), { residence: [9001], work: [] })

    await userEvent.setup().click(screen.getByRole('button', { name: '저장' }))

    const alert = await screen.findByRole('alert')
    expect(alert.textContent).toContain('시험 폐지구가 서울 중구로 바뀌었습니다')
    expect(alert.textContent).toContain('확인하고 다시 저장해 주세요')
    await waitFor(() => expect(screen.getByRole('button', { name: '서울 중구 빼기' })).toBeTruthy())
  })
})

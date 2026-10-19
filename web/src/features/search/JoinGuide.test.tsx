import { cleanup, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { TargetLibrary } from '../../api/types'
import { JoinGuide } from './JoinGuide'

afterEach(() => {
  cleanup()
  // jsdom has no matchMedia: back to "not a phone"
  delete (window as { matchMedia?: unknown }).matchMedia
})

function phoneWidth() {
  window.matchMedia = ((query: string) => ({
    matches: query === '(width <= 640px)',
    media: query,
    addEventListener: () => {},
    removeEventListener: () => {},
  })) as unknown as typeof window.matchMedia
}

const library: TargetLibrary = {
  id: 7,
  name: '울산동부도서관 전자책도서관',
  libraryType: 'EBOOK',
  platformType: 'KYOBO_V1',
  priorityGroup: 'OPEN',
  deferred: false,
  extraConditionText: null,
  visitRequired: false,
  eligibility: 'OPEN',
  eligibilitySummary: null,
  joinMethod: '울산동부도서관 홈페이지 신규회원 가입(본인인증) → 로그인 후 [전자책도서관]에서 대출',
  joinUrl: 'https://lib.example/join',
  joinCaution: null,
  joinNotice: null,
  homepageUrl: 'https://lib.example/',
  chosen: false,
  joinSurvey: 'SURVEYED',
  logoUrl: null,
  mobileSiteDiffers: false,
}

function renderGuide(overrides: Partial<TargetLibrary> = {}, book: string | null = 'https://lib.example/book/1') {
  const bookUrl = book ?? undefined
  const onOpenChange = vi.fn()
  const report = vi.fn(() => ({}))
  render(
    <JoinGuide open onOpenChange={onOpenChange} library={{ ...library, ...overrides }} bookUrl={bookUrl} report={report} />,
  )
  return { onOpenChange, report, guide: screen.getByRole('dialog') }
}

/** The join guide window (user 2026-10-19, pictures join-pc-a / join-phone-b). */
describe('join guide window', () => {
  it('on PC and tablets: a dialog named "… 가입 안내" with its own × and no sheet handle', async () => {
    const { guide, onOpenChange } = renderGuide()

    expect(screen.getByRole('dialog', { name: `${library.name} 가입 안내` })).toBe(guide)
    expect(screen.queryByTestId('sheet-handle')).toBeNull()
    // the eyebrow first, then the title
    expect(guide.querySelector('p')?.textContent).toBe('가입 방법')
    expect(guide.querySelector('h2')?.textContent).toBe(`${library.name} 가입 안내`)
    expect(guide.textContent).toContain('처음 이용한다면 아래 순서대로 준비해 주세요.')
    await userEvent.setup().click(within(guide).getByRole('button', { name: '닫기' }))
    expect(onOpenChange).toHaveBeenCalledWith(false)
  })

  it('on phones: the shared bottom sheet with the same content', () => {
    phoneWidth()
    const { guide } = renderGuide()

    expect(screen.getByRole('dialog', { name: `${library.name} 가입 안내` })).toBe(guide)
    expect(screen.getByTestId('sheet-handle')).toBeTruthy()
    expect(within(guide).getAllByRole('button', { name: '닫기' })).toHaveLength(1)
    expect(within(guide).getAllByRole('listitem')).toHaveLength(2)
    expect(within(guide).getByRole('link', { name: /공식 가입 안내 보기/ })).toBeTruthy()
  })

  it('lists the target, the way and what to bring', () => {
    const { guide } = renderGuide()
    const facts = guide.querySelector('dl')!

    expect([...facts.querySelectorAll('dt')].map((dt) => dt.textContent)).toEqual(['가입 대상', '가입 방법', '준비할 것'])
    expect([...facts.querySelectorAll('dd')].map((dd) => dd.textContent)).toEqual([
      '어디 살든 가입',
      '온라인 가입',
      '본인인증 수단',
    ])
  })

  it('a visit-only library: the rose [방문 가입] pill and "온라인 가입 후 도서관 방문"; no "준비할 것" when nothing is named', () => {
    const { guide } = renderGuide({ visitRequired: true, joinMethod: '국립중앙도서관 통합회원 가입' })
    const facts = guide.querySelector('dl')!

    expect([...facts.querySelectorAll('dt')].map((dt) => dt.textContent)).toEqual(['가입 대상', '가입 방법'])
    expect(within(facts).getByText('방문 가입').tagName).toBe('SPAN')
    expect(facts.textContent).toContain('온라인 가입 후 도서관 방문')
  })

  it('numbers the steps of a method with "→"', () => {
    const { guide } = renderGuide()

    const steps = within(guide).getAllByRole('listitem')
    expect(steps.map((step) => step.textContent)).toEqual([
      '1울산동부도서관 홈페이지 신규회원 가입(본인인증)',
      '2로그인 후 [전자책도서관]에서 대출',
    ])
  })

  it('shows a one-part method as a sentence, and the fallback without one', () => {
    renderGuide({ joinMethod: '도서관 홈페이지 가입 후 로그인' })
    expect(screen.queryByRole('list')).toBeNull()
    expect(screen.getByText('도서관 홈페이지 가입 후 로그인')).toBeTruthy()
    cleanup()

    renderGuide({ joinMethod: null })
    expect(screen.queryByRole('list')).toBeNull()
    expect(screen.getByText('도서관 홈페이지의 회원가입 안내를 확인해 주세요')).toBeTruthy()
  })

  it('keeps the extra condition, the caution and the notice under the steps, then the note', () => {
    const { guide } = renderGuide({
      extraConditionText: '책이음 회원 가입 가능',
      joinCaution: '회원증 발급은 거주자만',
      joinNotice: '시각장애인 이용 도서관',
    })
    const text = guide.textContent!

    expect(text).toContain('책이음 회원 가입 가능')
    expect(text).toContain('유의 회원증 발급은 거주자만')
    expect(text).toContain('안내 시각장애인 이용 도서관')
    expect(text.indexOf('로그인 후')).toBeLessThan(text.indexOf('책이음'))
    expect(text.indexOf('시각장애인')).toBeLessThan(text.indexOf('가입 조건은 바뀔 수 있어요.'))
  })

  it('links the book and the official join page in new tabs, each reporting its place', () => {
    const { guide, report } = renderGuide()

    const member = within(guide).getByRole('link', { name: /이미 회원이에요 · 책 보기/ })
    expect(member.getAttribute('href')).toBe('https://lib.example/book/1')
    expect(member.getAttribute('target')).toBe('_blank')
    const official = within(guide).getByRole('link', { name: /공식 가입 안내 보기/ })
    expect(official.getAttribute('href')).toBe('https://lib.example/join')
    expect(official.getAttribute('target')).toBe('_blank')
    expect(official.getAttribute('rel')).toContain('noopener')
    expect(report.mock.calls).toEqual([['result_card'], ['join_link']])
    expect(guide.textContent).toContain('가입과 대출은해당 도서관에서 진행해요.')
  })

  it('falls back to the front page, and leaves the links out without addresses', () => {
    const { report } = renderGuide({ joinUrl: null })
    expect(screen.getByRole('link', { name: /공식 가입 안내 보기/ }).getAttribute('href')).toBe('https://lib.example/')
    expect(report).toHaveBeenLastCalledWith('join_guide')
    cleanup()

    renderGuide({ joinUrl: null, homepageUrl: null }, null)
    expect(screen.queryByRole('link')).toBeNull()
    expect(screen.getByText(/가입과 대출은/)).toBeTruthy()
  })

  it('never links an address that is not http(s)', () => {
    renderGuide({ joinUrl: 'javascript:alert(1)', homepageUrl: null }, 'javascript:alert(1)')
    expect(screen.queryByRole('link')).toBeNull()
  })
})

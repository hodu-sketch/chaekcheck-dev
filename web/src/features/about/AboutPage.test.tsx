import { cleanup, render, screen, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { AboutPage } from './AboutPage'

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

function renderPage() {
  const fetchMock = vi.fn()
  vi.stubGlobal('fetch', fetchMock)
  render(
    <MemoryRouter initialEntries={['/about']}>
      <AboutPage />
    </MemoryRouter>,
  )
  return fetchMock
}

describe('책첵이란? (/about, user 2026-10-19)', () => {
  it('shows the headline and the lead, without buttons (user 2026-10-19)', () => {
    renderPage()
    expect(screen.getByText('책첵이란?')).toBeTruthy()
    expect(screen.getByRole('heading', { level: 1 }).textContent).toBe('도서관은 달라도,책 찾기는 한 번에.')
    expect(screen.getByText(/여러 전자도서관의 전자책을 한 번에 찾아 주는 서비스예요/)).toBeTruthy()
    expect(screen.queryByRole('link', { name: '읽고 싶은 전자책 찾기' })).toBeNull()
    expect(screen.queryByRole('link', { name: '지역 설정' })).toBeNull()
  })

  it('lists the three steps in order', () => {
    renderPage()
    expect(screen.getByRole('heading', { name: '이렇게 이용해 보세요' })).toBeTruthy()
    const steps = screen.getAllByRole('listitem')
    expect(steps.map((step) => step.querySelector('h3')?.textContent)).toEqual([
      '책을 검색하세요',
      '도서관을 비교하세요',
      '도서관에서 빌리세요',
    ])
    expect(steps.map((step) => step.querySelector('[aria-hidden]')?.textContent)).toEqual(['1', '2', '3'])
  })

  it('has three questions, only the first open', () => {
    renderPage()
    expect(screen.getByRole('heading', { name: '처음 이용할 때 궁금한 점' })).toBeTruthy()
    const details = [...document.querySelectorAll('details')]
    expect(details.map((d) => d.querySelector('summary')?.textContent)).toEqual([
      '다른 지역 도서관에도 가입할 수 있나요?',
      '책첵에 가입하면 전자책을 빌릴 수 있나요?',
      '대출 가능인데 도서관에서 빌릴 수 없어요.',
    ])
    expect(details.map((d) => d.open)).toEqual([true, false, false])
  })

  it('says where the information comes from and calls no server', () => {
    const fetchMock = renderPage()
    expect(screen.getByRole('heading', { name: '정보는 어디에서 가져오나요?' })).toBeTruthy()
    expect(screen.getByText(/이용 전 공식 안내를 확인해 주세요/)).toBeTruthy()
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it("keeps the old document's attribution, logo statement, contact and maker lines (출처와 문의)", () => {
    renderPage()
    const notes = screen.getByRole('region', { name: '출처와 문의' })
    expect(within(notes).getByText('사서추천도서 목록과 일부 표지는 국립중앙도서관 자료(공공누리 제1유형)예요.')).toBeTruthy()
    expect(
      within(notes).getByText('각 도서관의 로고와 이름은 그 기관의 것이며 책첵은 제휴·후원 관계가 없습니다.'),
    ).toBeTruthy()
    expect(within(notes).getByRole('link', { name: 'chaekcheck9104@gmail.com' }).getAttribute('href')).toBe(
      'mailto:chaekcheck9104@gmail.com',
    )
    expect(
      within(notes).getByRole('link', { name: 'https://github.com/hodu-sketch/chaekcheck-about' }).getAttribute('href'),
    ).toBe('https://github.com/hodu-sketch/chaekcheck-about')
    expect(document.body.textContent).not.toMatch(/조사한 내용입니다/)
  })
})

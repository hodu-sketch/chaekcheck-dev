import { cleanup, render, screen } from '@testing-library/react'
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
  it('shows the headline, the lead and the two links', () => {
    renderPage()
    expect(screen.getByText('책첵이란?')).toBeTruthy()
    expect(screen.getByRole('heading', { level: 1 }).textContent).toBe('도서관은 달라도,책 찾기는 한 번에.')
    expect(screen.getByText(/여러 전자도서관의 전자책을 한 번에 찾아 주는 서비스예요/)).toBeTruthy()
    expect(screen.getByRole('link', { name: '읽고 싶은 전자책 찾기' }).getAttribute('href')).toBe('/')
    expect(screen.getByRole('link', { name: '지역 설정' }).getAttribute('href')).toBe('/my-libraries')
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
})

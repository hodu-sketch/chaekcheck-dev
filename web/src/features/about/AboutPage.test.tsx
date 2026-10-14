import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { calledUrls, documentOf, fakeServer, json, problem, TWO_VERSIONS } from '../sitecontent/testSiteContent'
import { AboutPage } from './AboutPage'
import { surveyMonthLabel } from './aboutText'

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

/** The approved first version (plan `drafts/about.md`). */
const ABOUT_BODY = [
  '책첵은 여러 전자도서관에서 지금 빌릴 수 있는 전자책을 한 번에 찾아 주는 서비스예요.',
  '사서추천도서 목록과 일부 표지는 국립중앙도서관 자료(공공누리 제1유형)예요.',
  '각 도서관의 로고와 이름은 그 기관의 것이며 책첵은 제휴·후원 관계가 없습니다.',
  '문의·로고·표지 내림 요청: <chaekcheck9104@gmail.com>',
].join('\n\n')

const PARTNERSHIP = '각 도서관의 로고와 이름은 그 기관의 것이며 책첵은 제휴·후원 관계가 없습니다.'
const SURVEY_LINE = /가입 조건은 .*조사한 내용입니다/

function renderAt(path = '/about') {
  return render(
    <QueryClientProvider client={new QueryClient()}>
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route path="/about" element={<AboutPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

function serve(survey: () => Response, about: () => Response = () => json(200, documentOf('about', ABOUT_BODY))) {
  return fakeServer({ '/api/v1/documents/about': about, '/api/v1/join-surveys/latest': survey })
}

describe('S12 서비스 안내 (cycle 07, ADR-0059; cycle 16 text from the API)', () => {
  it('shows the about document with its date', async () => {
    const fetchMock = serve(() => json(200, { surveyedOn: null }))
    renderAt()

    expect(screen.getByRole('heading', { name: '서비스 안내' })).toBeTruthy()
    expect(await screen.findByText(PARTNERSHIP)).toBeTruthy()
    expect(screen.getByText('2026-10-04')).toBeTruthy()
    expect(screen.getByText(/사서추천도서 목록과 일부 표지는 국립중앙도서관 자료\(공공누리 제1유형\)예요/)).toBeTruthy()
    expect(calledUrls(fetchMock)).toContain('/api/v1/documents/about')
  })

  it('the contact e-mail is a mailto link (AC-12)', async () => {
    serve(() => json(200, { surveyedOn: null }))
    renderAt()

    expect((await screen.findByRole('link', { name: 'chaekcheck9104@gmail.com' })).getAttribute('href')).toBe(
      'mailto:chaekcheck9104@gmail.com',
    )
  })

  it('the date picker opens an earlier version with the small "이전 버전" mark (AC-2)', async () => {
    fakeServer({
      '/api/v1/join-surveys/latest': () => json(200, { surveyedOn: null }),
      '/api/v1/documents/about': () =>
        json(200, documentOf('about', '새 안내 글', { number: 2, effectiveOn: '2026-10-20' }, TWO_VERSIONS)),
      '/api/v1/documents/about?version=1': () =>
        json(200, documentOf('about', '옛 안내 글', { number: 1, current: false }, TWO_VERSIONS)),
    })
    renderAt()

    const picker = (await screen.findByRole('combobox', { name: '적용 기간' })) as HTMLSelectElement
    expect([...picker.options].map((option) => option.textContent)).toEqual([
      '2026-10-20 ~ 지금',
      '2026-10-04 ~ 2026-10-19 적용',
    ])
    expect(screen.queryByText('이전 버전')).toBeNull()

    await userEvent.setup().selectOptions(picker, '1')
    expect(await screen.findByText('옛 안내 글')).toBeTruthy()
    expect(screen.getByText('이전 버전')).toBeTruthy()
  })

  it('a version that does not exist is "찾을 수 없어요" (AC-9)', async () => {
    fakeServer({
      '/api/v1/join-surveys/latest': () => json(200, { surveyedOn: null }),
      '/api/v1/documents/about?version=5': () => problem(404, 'version_not_found'),
    })
    renderAt('/about?version=5')

    expect(await screen.findByText('찾을 수 없어요')).toBeTruthy()
    expect(screen.getByRole('link', { name: '지금 버전 보기' }).getAttribute('href')).toBe('/about')
  })

  it('shows grey lines while loading and 다시 시도 after a server error', async () => {
    let calls = 0
    serve(
      () => json(200, { surveyedOn: null }),
      () => (++calls <= 2 ? problem(500, 'internal_error') : json(200, documentOf('about', ABOUT_BODY))),
    )
    renderAt()

    expect(screen.getByRole('status', { name: '불러오는 중' })).toBeTruthy()
    expect(await screen.findByText('글을 불러오지 못했어요', undefined, { timeout: 3000 })).toBeTruthy()
    await userEvent.setup().click(screen.getByRole('button', { name: '다시 시도' }))
    expect(await screen.findByText(PARTNERSHIP)).toBeTruthy()
  })
})

describe('S12 join survey line (cycle 13; cycle 16 C6, AC-10)', () => {
  it('comes right under the whole text', async () => {
    serve(() => json(200, { surveyedOn: '2026-10-05' }))
    renderAt()

    const line = await screen.findByText('가입 조건은 2026년 10월에 조사한 내용입니다.')
    const paragraphs = [...document.querySelectorAll<HTMLElement>('article p')]
    expect(paragraphs.at(-1)).toBe(line)
    expect(paragraphs.at(-2)?.textContent).toBe('문의·로고·표지 내림 요청: chaekcheck9104@gmail.com')
    // not part of the text: the text block comes right before it
    expect(line.parentElement?.tagName).toBe('ARTICLE')
    expect(line.previousElementSibling?.textContent).toContain(PARTNERSHIP)
  })

  it('shows no line before the first survey date', async () => {
    serve(() => json(200, { surveyedOn: null }))
    renderAt()

    expect(await screen.findByText(PARTNERSHIP)).toBeTruthy()
    await new Promise((resolve) => setTimeout(resolve, 0))
    expect(screen.queryByText(SURVEY_LINE)).toBeNull()
  })

  it('shows no line when the survey request fails; the text still shows', async () => {
    serve(() => problem(500, 'internal_error'))
    renderAt()

    expect(await screen.findByText(PARTNERSHIP)).toBeTruthy()
    await new Promise((resolve) => setTimeout(resolve, 0))
    expect(screen.queryByText(SURVEY_LINE)).toBeNull()
  })
})

describe('surveyMonthLabel', () => {
  it('reads year and month from the string without a time-zone shift', () => {
    expect(surveyMonthLabel('2026-10-05')).toBe('2026년 10월')
    expect(surveyMonthLabel('2027-01-01')).toBe('2027년 1월')
  })

  it('gives null for a string that is not a date', () => {
    expect(surveyMonthLabel('')).toBeNull()
    expect(surveyMonthLabel('2026-13-01')).toBeNull()
  })
})

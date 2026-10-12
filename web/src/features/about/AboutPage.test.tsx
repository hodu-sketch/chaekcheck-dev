import { cleanup, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError, fetchLatestJoinSurvey } from '../../api/client'
import { AboutPage } from './AboutPage'
import { surveyMonthLabel } from './aboutText'

vi.mock('../../api/client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../api/client')>()),
  fetchLatestJoinSurvey: vi.fn(),
}))

const PARTNERSHIP = '각 도서관의 로고와 이름은 그 기관의 것이며 책첵은 제휴·후원 관계가 없습니다.'
const SURVEY_LINE = /가입 조건은 .*조사한 내용입니다/

beforeEach(() => {
  vi.mocked(fetchLatestJoinSurvey).mockResolvedValue({ surveyedOn: null })
})
afterEach(cleanup)

describe('S12 서비스 안내 (cycle 07, ADR-0059)', () => {
  it('says whose logos and names these are', () => {
    render(<AboutPage />)

    expect(screen.getByRole('heading', { name: '서비스 안내' })).toBeTruthy()
    expect(screen.getByText(PARTNERSHIP)).toBeTruthy()
  })
})

describe('S12 join survey line (cycle 13, AC-10)', () => {
  it('shows the survey month under the first paragraph, above the partnership notice', async () => {
    vi.mocked(fetchLatestJoinSurvey).mockResolvedValue({ surveyedOn: '2026-10-05' })
    render(<AboutPage />)

    const line = await screen.findByText('가입 조건은 2026년 10월에 조사한 내용입니다.')
    const paragraphs = [...document.querySelectorAll<HTMLElement>('p')]
    expect(paragraphs.indexOf(line)).toBe(1)
    expect(paragraphs[2]?.textContent).toBe(PARTNERSHIP)
  })

  it('shows no line before the first survey date', async () => {
    render(<AboutPage />)

    await vi.waitFor(() => expect(fetchLatestJoinSurvey).toHaveBeenCalled())
    await Promise.resolve()
    expect(screen.queryByText(SURVEY_LINE)).toBeNull()
    expect(screen.getByText(PARTNERSHIP)).toBeTruthy()
  })

  it('shows no line when the request fails', async () => {
    vi.mocked(fetchLatestJoinSurvey).mockRejectedValue(
      new ApiError(500, { title: 'Internal Server Error', status: 500, code: 'internal_error' }),
    )
    render(<AboutPage />)

    await vi.waitFor(() => expect(fetchLatestJoinSurvey).toHaveBeenCalled())
    await Promise.resolve()
    expect(screen.queryByText(SURVEY_LINE)).toBeNull()
    expect(screen.getByText(PARTNERSHIP)).toBeTruthy()
  })

  it('leaves the line out while loading and shows the rest at once', () => {
    vi.mocked(fetchLatestJoinSurvey).mockReturnValue(new Promise(() => {}))
    render(<AboutPage />)

    expect(screen.queryByText(SURVEY_LINE)).toBeNull()
    expect(screen.getByText(/책첵은 여러 전자도서관에서/)).toBeTruthy()
    expect(screen.getByText(PARTNERSHIP)).toBeTruthy()
    expect(screen.getByText('로고를 내려 달라는 요청은 연락처가 정해지면 이곳에 안내할게요.')).toBeTruthy()
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

  it('names the National Library of Korea for the picks and covers (cycle 14)', () => {
    render(<AboutPage />)

    expect(screen.getByText(/사서추천도서 목록과 일부 표지는 국립중앙도서관 자료\(공공누리 제1유형\)예요/)).toBeTruthy()
    expect(screen.queryByText(/출판유통/)).toBeNull()
  })
})

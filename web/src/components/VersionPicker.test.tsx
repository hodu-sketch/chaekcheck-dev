import { cleanup, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router'
import { afterEach, describe, expect, it } from 'vitest'
import type { DocumentVersionInfo } from '../api/types'
import { VersionPicker } from './VersionPicker'
import { dayBefore, versionPeriod } from './versionPeriod'

afterEach(cleanup)

const VERSIONS: DocumentVersionInfo[] = [
  { number: 3, effectiveOn: '2026-10-20', supersededOn: null },
  { number: 2, effectiveOn: '2026-10-12', supersededOn: '2026-10-20' },
  { number: 1, effectiveOn: '2026-10-12', supersededOn: '2026-10-12' },
]

function Where() {
  const location = useLocation()
  return <div data-testid="where">{location.pathname + location.search}</div>
}

function renderAt(path: string, versions: DocumentVersionInfo[], shown: number) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route
          path="*"
          element={
            <>
              <VersionPicker versions={versions} shown={shown} />
              <Where />
            </>
          }
        />
      </Routes>
    </MemoryRouter>,
  )
}

const where = () => screen.getByTestId('where').textContent

describe('version period words (user decision 2026-10-13)', () => {
  it('current, past and same-day versions', () => {
    expect(versionPeriod(VERSIONS[0])).toBe('2026-10-20 ~ 지금')
    expect(versionPeriod(VERSIONS[1])).toBe('2026-10-12 ~ 2026-10-19 적용')
    expect(versionPeriod(VERSIONS[2])).toBe('2026-10-12 적용 (같은 날 바뀜)')
  })

  it('takes one day off across months and years', () => {
    expect(dayBefore('2026-11-01')).toBe('2026-10-31')
    expect(dayBefore('2027-01-01')).toBe('2026-12-31')
    expect(dayBefore('2028-03-01')).toBe('2028-02-29')
  })
})

describe('VersionPicker (cycle 16, AC-2)', () => {
  it('lists every version with its period and shows the one on screen', () => {
    renderAt('/terms', VERSIONS, 3)

    const picker = screen.getByRole('combobox', { name: '적용 기간' }) as HTMLSelectElement
    expect([...picker.options].map((option) => option.textContent)).toEqual([
      '2026-10-20 ~ 지금',
      '2026-10-12 ~ 2026-10-19 적용',
      '2026-10-12 적용 (같은 날 바뀜)',
    ])
    expect(picker.value).toBe('3')
  })

  it('is hidden when there is only one version', () => {
    renderAt('/terms', [{ number: 1, effectiveOn: '2026-10-04', supersededOn: null }], 1)

    expect(screen.queryByRole('combobox')).toBeNull()
  })

  it('choosing an earlier version opens ?version=n, the current one the address without it', async () => {
    renderAt('/notices/7?x=1', VERSIONS, 3)
    const user = userEvent.setup()

    await user.selectOptions(screen.getByRole('combobox'), '2')
    expect(where()).toBe('/notices/7?x=1&version=2')

    await user.selectOptions(screen.getByRole('combobox'), '3')
    expect(where()).toBe('/notices/7?x=1')
  })
})

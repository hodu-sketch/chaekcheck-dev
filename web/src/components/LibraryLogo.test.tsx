import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it } from 'vitest'
import { LibraryLogo } from './LibraryLogo'

afterEach(cleanup)

/** Cycle 07 (ADR-0059, AC-20): own logo, or nothing when there is none or it fails to load. */
describe('LibraryLogo', () => {
  it('shows the logo with the library name as its text', () => {
    render(<LibraryLogo name="고흥군립도서관" logoUrl="/library-logos/2.png" homepageUrl="https://lib.test/" size={20} />)

    const img = screen.getByRole('img', { name: '고흥군립도서관' })
    expect(img.getAttribute('src')).toBe('/library-logos/2.png')
    expect(img.closest('a')?.getAttribute('target')).toBe('_blank')
  })

  it('shows nothing without a logo, and when the file fails', () => {
    const { container, unmount } = render(
      <LibraryLogo name="후보" logoUrl={null} homepageUrl="https://lib.test/" size={24} />,
    )
    expect(container.innerHTML).toBe('')
    unmount()

    const failed = render(<LibraryLogo name="후보" logoUrl="/library-logos/9.png" homepageUrl="https://lib.test/" size={24} />)
    fireEvent.error(screen.getByRole('img'))
    expect(failed.container.innerHTML).toBe('')
  })

  it('never uses a logo address that is not ours, nor a non-http home page', () => {
    render(<LibraryLogo name="이상한 곳" logoUrl="https://evil.test/x.png" homepageUrl="javascript:alert(1)" size={20} />)

    expect(screen.queryByRole('img')).toBeNull()
    expect(screen.queryByRole('link')).toBeNull()
  })
})

import { cleanup, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it } from 'vitest'
import { Markdown } from './Markdown'

afterEach(cleanup)

describe('Markdown (cycle 16, C2, AC-6)', () => {
  it('does not draw HTML in the text as elements', () => {
    const { container } = render(
      <Markdown>{'앞 글\n\n<script>alert(1)</script>\n\n<img src="x" onerror="alert(2)">\n\n뒤 글 <b>굵게?</b>'}</Markdown>,
    )

    expect(container.querySelector('script')).toBeNull()
    expect(container.querySelector('img')).toBeNull()
    expect(container.querySelector('b')).toBeNull()
    expect(container.querySelector('[onerror]')).toBeNull()
    expect(screen.getByText('앞 글')).toBeTruthy()
  })

  it('empties a javascript: link', () => {
    render(<Markdown>{'[x](javascript:alert(1)) [y](JavaScript:alert(2))'}</Markdown>)

    for (const name of ['x', 'y']) {
      const href = screen.getByText(name).closest('a')?.getAttribute('href') ?? ''
      expect(href.toLowerCase()).not.toContain('javascript:')
    }
  })

  it('keeps http, https and mailto links; an e-mail in <…> becomes a mailto link (AC-12)', () => {
    render(<Markdown>{'문의: <chaekcheck9104@gmail.com>\n\n[사이트](https://example.com) [옛](http://example.org)'}</Markdown>)

    expect(screen.getByRole('link', { name: 'chaekcheck9104@gmail.com' }).getAttribute('href')).toBe(
      'mailto:chaekcheck9104@gmail.com',
    )
    expect(screen.getByRole('link', { name: '사이트' }).getAttribute('href')).toBe('https://example.com')
    expect(screen.getByRole('link', { name: '옛' }).getAttribute('href')).toBe('http://example.org')
  })

  it('draws headings, lists, bold and paragraphs', () => {
    const { container } = render(
      <Markdown>{'## 1. 수집 항목\n\n첫 문단에 **굵은 말**이 있어요.\n\n- 하나\n- 둘\n\n1. 첫째\n2. 둘째'}</Markdown>,
    )

    expect(screen.getByRole('heading', { level: 2, name: '1. 수집 항목' })).toBeTruthy()
    expect(container.querySelector('strong')?.textContent).toBe('굵은 말')
    expect(container.querySelectorAll('ul > li')).toHaveLength(2)
    expect(container.querySelectorAll('ol > li')).toHaveLength(2)
    expect(container.querySelector('p')?.textContent).toBe('첫 문단에 굵은 말이 있어요.')
  })

  it('leaves out elements outside the allowed ones but keeps their text', () => {
    const { container } = render(<Markdown>{'![표지](https://example.com/a.png)\n\n> 인용\n\n`코드` *기울임*'}</Markdown>)

    expect(container.querySelector('img')).toBeNull()
    expect(container.querySelector('blockquote')).toBeNull()
    expect(container.querySelector('code')).toBeNull()
    expect(container.querySelector('em')).toBeNull()
    expect(container.textContent).toContain('인용')
    expect(container.textContent).toContain('코드 기울임')
  })
})

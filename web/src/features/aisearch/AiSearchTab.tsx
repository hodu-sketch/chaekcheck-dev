import { useId, useLayoutEffect, useRef, useState, type FormEvent, type ReactNode } from 'react'
import { useNavigate } from 'react-router'
import { startWithQuestion } from './aiChatStore'
import { AI_TEXT, QUESTION_MAX, QUESTION_MIN, shownAllExamples, shownExamples } from './aiSearchText'
import styles from './AiSearchTab.module.css'

/**
 * S1 "AI" tab (cycle 24 step 9): a sentence box with [찾기]. No 상세검색 and no ⚙ [확정]. The example chips gave way to
 * an example question in the box's placeholder (user 2026-10-25: "아래 질문 고르는 거 빼고 … 안 건들고 그냥 검색 누르면
 * 그 질문들 검색되게"): the first of this page load's drawn examples that fits the box whole; when fewer than two of
 * them fit (phones), the first that fits of all examples, short ones too, so a refresh still changes it (user
 * 2026-10-25: "새로고침을 해도 안 바뀌는데"); none fitting gives 「어떤 책을 찾으세요?」. Measured again once the web
 * font is in: measured with the fallback font, a long example looked as if it fitted and was cut.
 * [찾기] on an empty box asks the example shown. A question under 2 characters is not sent; the box takes at most 200. Searching puts the
 * question in this tab's conversation and opens `/ai-search`; the address never holds it (R2-2 [확정]). The text stays
 * while the tabs change because the hidden tab stays mounted (R1-3).
 */
/** Room kept free after an example in the box, in CSS pixels. */
const FIT_SPARE_PX = 12

export function AiSearchTab({ leading }: { leading?: ReactNode }) {
  const id = useId()
  const navigate = useNavigate()
  const [text, setText] = useState('')
  const [tooShort, setTooShort] = useState(false)
  const [example, setExample] = useState<string | null>(() => shownExamples()[0] ?? null)
  const input = useRef<HTMLInputElement>(null)

  // the example that fits whole, again when the web font is in and whenever the box's width changes; a box not laid
  // out (hidden, or jsdom) keeps the one it has
  useLayoutEffect(() => {
    const box = input.current
    if (!box) return
    const fit = () => {
      const style = getComputedStyle(box)
      const room = box.clientWidth - parseFloat(style.paddingLeft) - parseFloat(style.paddingRight)
      if (!(room > 0)) return
      const context = document.createElement('canvas').getContext('2d')
      if (!context) return
      context.font = style.font
      // a little to spare: a phone drew 「세네카와 쇼펜하우어의 철학」 cut where the canvas measured it as just fitting
      const fits = (question: string) => context.measureText(question).width <= room - FIT_SPARE_PX
      const long = shownExamples().filter(fits)
      setExample(long.length >= 2 ? long[0] : (shownAllExamples().find(fits) ?? null))
    }
    fit()
    document.fonts?.load(getComputedStyle(box).font, '가').then(fit, () => {})
    if (typeof ResizeObserver === 'undefined') return
    const observer = new ResizeObserver(fit)
    observer.observe(box)
    return () => observer.disconnect()
  }, [])

  const start = (question: string) => {
    const trimmed = question.trim()
    if ([...trimmed].length < QUESTION_MIN) {
      setTooShort(true)
      return
    }
    startWithQuestion(trimmed)
    navigate('/ai-search')
  }

  const handleSubmit = (event: FormEvent) => {
    event.preventDefault()
    start(text.trim() === '' && example ? example : text)
  }

  return (
    <form className={styles.form} role="search" aria-label="AI 책 찾기" onSubmit={handleSubmit} noValidate>
      <div className={styles.field} data-invalid={tooShort ? true : undefined}>
        {leading}
        <label htmlFor={`${id}-question`} className="visually-hidden">
          찾는 책을 문장으로
        </label>
        <input
          id={`${id}-question`}
          ref={input}
          className={styles.input}
          type="search"
          value={text}
          maxLength={QUESTION_MAX}
          placeholder={example ?? AI_TEXT.placeholder}
          autoComplete="off"
          aria-invalid={tooShort ? true : undefined}
          aria-describedby={tooShort ? `${id}-error` : undefined}
          onChange={(event) => {
            setText(event.target.value)
            if (tooShort) setTooShort(false)
          }}
        />
        <button type="submit" className={styles.submit}>
          {AI_TEXT.find}
        </button>
      </div>
      {tooShort && (
        <p id={`${id}-error`} className={styles.error} role="alert">
          {AI_TEXT.tooShort}
        </p>
      )}
    </form>
  )
}

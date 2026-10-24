import { useId, useState, type FormEvent } from 'react'
import { useNavigate } from 'react-router'
import { startWithQuestion } from './aiChatStore'
import { AI_TEXT, EXAMPLE_QUESTIONS, QUESTION_MAX, QUESTION_MIN } from './aiSearchText'
import styles from './AiSearchTab.module.css'

/**
 * S1 "AI" tab (cycle 24 step 9): a sentence box with [찾기] and the example chips (a chip searches at once). No
 * 상세검색 and no ⚙ [확정]. A question under 2 characters is not sent; the box takes at most 200. Searching puts the
 * question in this tab's conversation and opens `/ai-search`; the address never holds it (R2-2 [확정]). The text stays
 * while the tabs change because the hidden tab stays mounted (R1-3).
 */
export function AiSearchTab() {
  const id = useId()
  const navigate = useNavigate()
  const [text, setText] = useState('')
  const [tooShort, setTooShort] = useState(false)

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
    start(text)
  }

  return (
    <form className={styles.form} role="search" aria-label="AI 책 찾기" onSubmit={handleSubmit} noValidate>
      <div className={styles.field} data-invalid={tooShort ? true : undefined}>
        <label htmlFor={`${id}-question`} className="visually-hidden">
          찾는 책을 문장으로
        </label>
        <input
          id={`${id}-question`}
          className={styles.input}
          type="search"
          value={text}
          maxLength={QUESTION_MAX}
          placeholder={AI_TEXT.placeholder}
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
      <ul className={styles.chips} aria-label="예시 질문">
        {EXAMPLE_QUESTIONS.map((question) => (
          <li key={question}>
            <button type="button" className={styles.chip} onClick={() => start(question)}>
              {question}
            </button>
          </li>
        ))}
      </ul>
    </form>
  )
}

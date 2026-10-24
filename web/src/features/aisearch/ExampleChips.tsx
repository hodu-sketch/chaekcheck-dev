import { useLayoutEffect, useRef } from 'react'
import { useNavigate } from 'react-router'
import { startWithQuestion } from './aiChatStore'
import { EXAMPLES_SHOWN, shownExamples } from './aiSearchText'
import styles from './ExampleChips.module.css'

/**
 * The example questions under the first screen's box, in an order drawn per page load: up to three that fit whole on
 * one line (user 2026-10-24: "3개 정도", "내용이 잘라지지 않은 선에서 적당히"). A chip starts the AI search with its
 * question at once. Under the direct box too (user 2026-10-24: "ai 검색이 아니더라도 이건 놔주라").
 */
export function ExampleChips() {
  const navigate = useNavigate()
  const list = useRef<HTMLUListElement>(null)

  // one line of whole chips, at most three: in the drawn order each chip is kept when it still fits on the first line,
  // else hidden and the next one tried (a shorter one may fit); again whenever the width changes
  useLayoutEffect(() => {
    const ul = list.current
    if (!ul) return
    const fit = () => {
      const items = [...ul.children] as HTMLElement[]
      items.forEach((item) => (item.hidden = true))
      let kept = 0
      let top: number | null = null
      for (const item of items) {
        if (kept === EXAMPLES_SHOWN) break
        item.hidden = false
        top ??= item.offsetTop
        if (item.offsetTop > top) item.hidden = true
        else kept++
      }
    }
    fit()
    if (typeof ResizeObserver === 'undefined') return
    const observer = new ResizeObserver(fit)
    observer.observe(ul)
    return () => observer.disconnect()
  }, [])

  return (
    <ul ref={list} className={styles.chips} aria-label="예시 질문">
      {shownExamples().map((question) => (
        <li key={question}>
          <button
            type="button"
            className={styles.chip}
            onClick={() => {
              startWithQuestion(question)
              navigate('/ai-search')
            }}
          >
            {question}
          </button>
        </li>
      ))}
    </ul>
  )
}

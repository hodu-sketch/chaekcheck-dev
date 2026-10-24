import { useNavigate } from 'react-router'
import { startWithQuestion } from './aiChatStore'
import { EXAMPLE_QUESTIONS } from './aiSearchText'
import styles from './ExampleChips.module.css'

/**
 * The example questions under the first screen's box: a chip starts the AI search with its question at once. Under the
 * direct box too (user 2026-10-24: "ai 검색이 아니더라도 이건 놔주라").
 */
export function ExampleChips() {
  const navigate = useNavigate()
  return (
    <ul className={styles.chips} aria-label="예시 질문">
      {EXAMPLE_QUESTIONS.map((question) => (
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

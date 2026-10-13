import { useEffect, useState } from 'react'
import { fetchLatestJoinSurvey } from '../../api/client'
import styles from '../auth/LegalPage.module.css'
import { surveyMonthLabel } from './aboutText'

/**
 * S12 `/about` (cycle 07, ADR-0059): what 책첵 is, whose logos and names these are, and where to ask for a logo to be
 * taken down. The same white card as S8·S9. Open without login.
 *
 * Cycle 13 (spec R5, ADR-0093): under the first paragraph, the month the join rules were surveyed. While it loads the
 * line is left out and the rest shows at once; no date yet or an error leaves it out too.
 */
export function AboutPage() {
  const [surveyMonth, setSurveyMonth] = useState<string | null>(null)

  useEffect(() => {
    const controller = new AbortController()
    fetchLatestJoinSurvey(controller.signal)
      .then((latest) => setSurveyMonth(latest.surveyedOn ? surveyMonthLabel(latest.surveyedOn) : null))
      .catch(() => {
        if (controller.signal.aborted) return
        setSurveyMonth(null)
      })
    return () => controller.abort()
  }, [])

  return (
    <article className={styles.page} aria-labelledby="about-title">
      <header className={styles.head}>
        <h1 id="about-title" className={styles.title}>
          서비스 안내
        </h1>
      </header>
      <div className={styles.body}>
        <p>
          책첵은 여러 전자도서관에서 지금 빌릴 수 있는 전자책을 한 번에 찾아 주는 서비스예요. 수량은 각 도서관의 공개
          검색 결과를 기준으로 보여 줘요.
        </p>
        {surveyMonth && <p>가입 조건은 {surveyMonth}에 조사한 내용입니다.</p>}
        {/*
          cycle 14 (ADR-0091) [확정 문구]. TODO(확인 필요): the contact for right holders who do not want a cover shown;
          TODO(확인 필요): 배포 전 국립중앙도서관 표지 이용 조건 확인
        */}
        <p>
          사서추천도서 목록과 일부 표지는 국립중앙도서관 자료(공공누리 제1유형)예요. 표지 게시를 원하지 않는 권리자를
          위한 연락처는 정해지면 이곳에 안내할게요.
        </p>
        <p>각 도서관의 로고와 이름은 그 기관의 것이며 책첵은 제휴·후원 관계가 없습니다.</p>
        {/* TODO(확인 필요): the service's own e-mail address for logo take-down requests; the user will give it */}
        <p>로고를 내려 달라는 요청은 연락처가 정해지면 이곳에 안내할게요.</p>
      </div>
    </article>
  )
}

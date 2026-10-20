import styles from './AboutPage.module.css'

const STEPS = [
  { title: '책을 검색하세요', text: '지역을 고르지 않아도 지역 제한 없는 도서관부터 찾아요.' },
  { title: '도서관을 비교하세요', text: '가입 대상과 방법, 책의 대출 상태를 확인하세요.' },
  { title: '도서관에서 빌리세요', text: '처음이라면 가입·인증을 마친 뒤 해당 도서관에서 대출하세요.' },
]

const QUESTIONS = [
  {
    q: '다른 지역 도서관에도 가입할 수 있나요?',
    a: '일부 도서관은 사는 곳과 관계없이 가입할 수 있어요. 일하는 곳으로 가입할 수 있는 곳도 있어요. 본인 확인이나 방문 같은 세부 조건은 도서관마다 달라요.',
  },
  {
    q: '책첵에 가입하면 전자책을 빌릴 수 있나요?',
    a: '아니요. 책첵은 책과 도서관을 찾아 주는 곳이에요. 대출은 각 도서관에 가입한 뒤 그 도서관에서 해요.',
  },
  {
    q: '대출 가능인데 도서관에서 빌릴 수 없어요.',
    a: '책첵이 도서관 사이트를 확인한 때와 지금이 다를 수 있어요. 대출회원 인증이 필요하거나 대출 권수가 정해진 곳도 있으니 도서관 안내를 확인해 주세요.',
  },
  // a question of Q&A, not a section of its own (user 2026-10-20: "정보는 어디에서 가져오나요? 이걸 대출 가능인데
  // 도서관에서 빌릴 수 없어요 처럼 Q&A에 넣으라는거지")
  {
    q: '정보는 어디에서 가져오나요?',
    a: '책 정보와 가입 조건은 각 도서관의 안내를 바탕으로 정리해요. 도서관의 운영 정책과 책의 대출 상태는 달라질 수 있어요. 이용 전 공식 안내를 확인해 주세요.',
  },
]

/**
 * `/about` "책첵이란?" (user decision 2026-10-19, with reference pictures): a static page, reached from the last header
 * menu and the footer. It replaced the S12 서비스 안내 screen, which drew the server's `about` document (cycle 16,
 * ADR-0098) and the join survey month; this page calls neither. The document's attribution, logo statement, contact
 * and maker lines are kept word for word in 출처와 문의 at the end.
 */
export function AboutPage() {
  return (
    <article className={styles.page} aria-labelledby="about-title">
      <section className={styles.hero}>
        <p className={styles.eyebrow}>책첵이란?</p>
        <h1 id="about-title" className={styles.headline}>
          도서관은 달라도,
          <br />
          책 찾기는 한 번에.
        </h1>
        <p className={styles.lead}>
          내가 사는 지역 밖에도 빌릴 수 있는 전자도서관이 있어요. 책첵은 서로 다른 가입 조건을 정리하고, 여러
          전자도서관의 전자책을 한 번에 찾아 주는 서비스예요.
        </p>
      </section>

      <section className={styles.card} aria-labelledby="about-steps">
        <h2 id="about-steps" className={styles.cardTitle}>
          이렇게 이용해 보세요
        </h2>
        <ol className={styles.steps}>
          {STEPS.map((step, index) => (
            <li key={step.title} className={styles.step}>
              <span className={styles.number} aria-hidden="true">
                {index + 1}
              </span>
              <div>
                <h3 className={styles.stepTitle}>{step.title}</h3>
                <p className={styles.stepText}>{step.text}</p>
              </div>
            </li>
          ))}
        </ol>
      </section>

      <section className={styles.section} aria-labelledby="about-faq">
        <h2 id="about-faq" className={styles.sectionTitle}>
          {/* user 2026-10-20: "처음 이용할 때 궁금한 점 이거 Q&A 이걸로바꾸고 처음 거 그냥 펼쳐져있는데 기본 닫는걸로" */}
          Q&amp;A
        </h2>
        <div className={styles.questions}>
          {QUESTIONS.map((item) => (
            <details key={item.q} className={styles.question}>
              <summary className={styles.summary}>{item.q}</summary>
              <p className={styles.answer}>{item.a}</p>
            </details>
          ))}
        </div>
      </section>

      {/* kept from the server's `about` document, same wording (the 공공누리 제1유형 attribution is required) */}
      <section className={styles.notes} aria-labelledby="about-notes">
        <h2 id="about-notes" className={styles.notesTitle}>
          출처와 문의
        </h2>
        <p>사서추천도서 목록과 일부 표지는 국립중앙도서관 자료(공공누리 제1유형)예요.</p>
        <p>각 도서관의 로고와 이름은 그 기관의 것이며 책첵은 제휴·후원 관계가 없습니다.</p>
        {/* CC BY 4.0 attribution of the IP list behind the first screen's example region (user 2026-10-20) */}
        <p>
          첫 화면 지역 예시의 IP 위치 정보:{' '}
          <a href="https://db-ip.com" target="_blank" rel="noopener noreferrer">
            IP Geolocation by DB-IP
          </a>{' '}
          (CC BY 4.0)
        </p>
        <p>
          문의: <a href="mailto:chaekcheck9104@gmail.com">chaekcheck9104@gmail.com</a>
        </p>
        {/* no 만든 사람 line (user 2026-10-20: "이거 빼줘") */}
      </section>
    </article>
  )
}

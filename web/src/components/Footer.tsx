import styles from './Footer.module.css'

/** AC-12: data source and contact. */
export function Footer() {
  return (
    <footer className={styles.footer}>
      <div className={styles.inner}>
        <p>수량은 각 도서관의 공개 검색 결과 기준이며, 도서관 사이트와 잠시 다를 수 있어요.</p>
        {/* TODO: contact is not decided yet (handoff: 연락처 값은 사용자에게 받은 뒤 채움, AC-12) */}
        <p className={styles.contact}>문의: 준비 중</p>
      </div>
    </footer>
  )
}

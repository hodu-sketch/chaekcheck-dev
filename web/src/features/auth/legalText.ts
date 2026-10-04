/**
 * Wording of the sign-up notice and the documents (S6, S8, S9). Placeholder text until the user reviews it before
 * deployment [확인 필요: 사용자 검토, handoff "사용자가 할 것"]; only the retention period is decided.
 * The document versions come from the server (`GET /api/v1/auth/signup`), never from here (C27).
 */

/** Shown on screen next to every text that is not reviewed yet. */
export const NOT_REVIEWED = '검토 전 문구'

/** 개인정보 보호법 제15조②: the four things a collection notice must say. */
export const COLLECTION_NOTICE: ReadonlyArray<{ label: string; text: string; reviewed: boolean }> = [
  {
    label: '목적',
    // TODO(확인 필요): wording to be reviewed by the user
    text: '회원 식별과 로그인 유지',
    reviewed: false,
  },
  {
    label: '항목',
    // TODO(확인 필요): Naver sends only the user identifier when no item is chosen (console, 2026-10-04); Kakao consent items are off
    text: '네이버 또는 카카오의 회원 식별 번호',
    reviewed: false,
  },
  {
    label: '보유·이용 기간',
    // [확정] (spec, design-delta constraint 29)
    text: '탈퇴할 때까지, 또는 마지막 로그인 뒤 1년',
    reviewed: true,
  },
  {
    label: '거부할 권리와 불이익',
    // TODO(확인 필요): wording to be reviewed by the user
    text: '동의하지 않을 수 있어요. 동의하지 않으면 가입할 수 없지만, 책 검색은 로그인 없이 그대로 쓸 수 있어요',
    reviewed: false,
  },
]

/** Full text of the terms of service; placeholder. TODO(확인 필요): the user writes or reviews it. */
export const TERMS_OF_SERVICE_BODY: ReadonlyArray<string> = [
  '이 문서는 검토 전 자리 글입니다. 배포 전에 실제 이용약관으로 바꿉니다.',
  '책첵은 여러 전자도서관의 공개 검색 결과를 모아 보여 주는 서비스입니다. 대출과 예약은 각 도서관에서 합니다.',
]

/** Full text of the collection and use consent; placeholder. TODO(확인 필요): the user writes or reviews it. */
export const PRIVACY_COLLECTION_BODY: ReadonlyArray<string> = [
  '이 문서는 검토 전 자리 글입니다. 배포 전에 실제 수집·이용 동의 문구로 바꿉니다.',
  '수집하는 항목, 목적, 보유 기간은 위 표와 같습니다.',
]

/**
 * The privacy policy (S9): the items 개인정보 보호법 제30조① and 시행령 제31조① require (plan research.md 5절), each with
 * placeholder text. TODO(확인 필요): the user writes or reviews every section before deployment; the contact of the
 * protection officer (the operator) is not decided yet.
 */
export const PRIVACY_POLICY_SECTIONS: ReadonlyArray<{ heading: string; paragraphs: ReadonlyArray<string> }> = [
  { heading: '처리하는 개인정보와 목적', paragraphs: ['네이버 또는 카카오의 회원 식별 번호를 회원 식별과 로그인 유지에 씁니다.'] },
  { heading: '보유·이용 기간', paragraphs: ['탈퇴할 때까지, 또는 마지막 로그인 뒤 1년까지 보유합니다.'] },
  { heading: '파기 절차와 방법', paragraphs: ['보유 기간이 끝나거나 탈퇴하면 지체 없이 데이터베이스에서 지웁니다.'] },
  { heading: '제3자 제공과 처리 위탁', paragraphs: ['검토 전입니다. 제공·위탁이 있으면 대상과 목적을 적습니다.'] },
  { heading: '정보주체의 권리와 행사 방법', paragraphs: ['설정의 "탈퇴"로 언제든 계정과 동의 기록을 지울 수 있습니다.'] },
  { heading: '안전성 확보 조치', paragraphs: ['검토 전입니다. 접근 통제, 전송 구간 암호화 등을 적습니다.'] },
  { heading: '개인정보 보호책임자', paragraphs: ['운영자 본인이 보호책임자입니다. 연락처: 준비 중'] },
  { heading: '처리방침의 변경', paragraphs: ['바뀌면 시행일과 함께 이 화면에 알립니다.'] },
]

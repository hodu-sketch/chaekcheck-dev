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

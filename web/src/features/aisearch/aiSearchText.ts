/**
 * The AI search's screen text (cycle 24 handoff "문구표"). [확정] lines are the user's; [안] lines are proposals.
 */
export const AI_TEXT = {
  tabAi: 'AI', // [확정] R1-1
  tabDirect: '직접', // [확정] R1-1
  placeholder: '어떤 책을 찾으세요? 예: 잠들기 전에 조금씩 읽을 과학책', // [안] flow S1
  find: '찾기',
  tooShort: '두 글자 이상 써 주세요', // AC-16
  notice: '책 설명은 AI가 질문에 맞춰 썼어요. 실제 내용과 다를 수 있어요', // [확정] R9-2
  loading: '책을 찾고 있어요',
  myLibraries: (count: number) => `내 도서관 ${count}곳`,
  noText: '지금은 설명을 쓰지 못했어요', // [확정] R6-2, R12-4
  noMatch: '맞는 책을 찾지 못했어요. 다른 말로 써 보시겠어요?', // [확정] R5-1
  tooMany: '잠시 뒤에 다시 써 주세요', // R7-1
  paused: '오늘은 AI 책 찾기를 쉬어요. 직접 찾기를 써 주세요', // R8-1 [안]
  toDirect: '직접 찾기로',
  connection: '연결이 원활하지 않아요', // [안]
  retry: '다시 시도',
  failed: '답을 쓰다가 문제가 생겼어요', // [안] for the error event
  titleGuess: (title: string) => `「${title}」을(를) 찾으시나요?`, // [확정] R13-3, AC-15 ("을(를)" as it is)
  titleGuessButton: '직접 찾기에서 보기',
  tooLong: '새 대화를 시작해 주세요', // R11-3
  newChat: '새 대화',
  send: '보내기',
  summary: (place: string) => `내 도서관: ${place}`, // [안] flow S30
} as const

/**
 * The example chips (user 2026-10-24: four topics of this month's news and best sellers, each checked to answer with
 * listed books; dev docs/plan-feedback.md `# 사이클 24` "8단계 뒤 사용자 결정"). Fixed for now; a refresh from the news is
 * asked of plan.
 */
export const EXAMPLE_QUESTIONS = [
  '요즘 경제 흐름을 쉽게 알려 주는 책',
  '흔들리는 마음을 다잡는 철학 책',
  '프로젝트 헤일메리처럼 빠져드는 SF 소설',
  '쌀쌀한 가을밤에 읽기 좋은 소설',
] as const

/** Question length (design decision #8 [확정]), the same as the server. */
export const QUESTION_MIN = 2
export const QUESTION_MAX = 200
/** Questions in one conversation (R11-3 [확정]) and earlier turns sent (R11-3). */
export const TURNS_MAX = 20
export const HISTORY_SENT = 5

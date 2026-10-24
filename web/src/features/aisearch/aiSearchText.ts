/**
 * The AI search's screen text (cycle 24 handoff "문구표"). [확정] lines are the user's; [안] lines are proposals.
 */
export const AI_TEXT = {
  tabAi: 'AI', // [확정] R1-1
  tabDirect: '직접', // [확정] R1-1
  placeholder: '어떤 책을 찾으세요? 예: 잠들기 전에 조금씩 읽을 과학책', // [안] flow S1
  // the title of an empty conversation, over the box in the middle as ChatGPT's new chat (user 2026-10-24: "정정해")
  emptyTitle: '어떤 책을 찾으세요?',
  emptyPlaceholder: '예: 잠들기 전에 조금씩 읽을 과학책',
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
} as const

/**
 * The example questions (user 2026-10-24: these eight, "메인에 ai검색문구 이 중에서 랜덤으로 3개 정도 나오게 해줘"; the
 * four of the morning, from this month's news, were replaced). A refresh from the news is asked of plan.
 */
export const EXAMPLE_QUESTIONS = [
  'AI 시대의 인간성, 철학과 기술에 대한 성찰',
  '미래 소비 트렌드와 경제적 자산 관리',
  '세네카와 쇼펜하우어의 철학',
  '뇌과학과 인공지능으로 인간의 생각을 이해',
  '한국사와 역사 속 미스터리',
  '반전과 추리가 돋보이는 최신 미스터리 소설',
  '인간관계와 삶의 고민을 위로하는 심리 에세이',
  '고전 문학과 철학을 현대적인 관점에서 읽기',
] as const

/** How many example chips show at once (user 2026-10-24: "3개 정도"). */
export const EXAMPLES_SHOWN = 3

/** `count` different questions in a random order. */
export function pickExamples(count = EXAMPLES_SHOWN, random: () => number = Math.random): string[] {
  const pool: string[] = [...EXAMPLE_QUESTIONS]
  for (let i = pool.length - 1; i > 0; i--) {
    const j = Math.floor(random() * (i + 1))
    ;[pool[i], pool[j]] = [pool[j], pool[i]]
  }
  return pool.slice(0, count)
}

let shown: string[] | null = null

/**
 * The examples of this page load, all of them in an order drawn once, so the AI box, the direct box and the chat show
 * the same ones: the first {@link EXAMPLES_SHOWN}, or on one line the first that fit whole.
 */
export function shownExamples(): string[] {
  shown ??= pickExamples(EXAMPLE_QUESTIONS.length)
  return shown
}

/** Question length (design decision #8 [확정]), the same as the server. */
export const QUESTION_MIN = 2
export const QUESTION_MAX = 200
/** Questions in one conversation (R11-3 [확정]) and earlier turns sent (R11-3). */
export const TURNS_MAX = 20
export const HISTORY_SENT = 5

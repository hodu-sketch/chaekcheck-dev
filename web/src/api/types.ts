// Response types. Field names match design-delta (camelCase).

/**
 * UNSURVEYED (cycle 07, ADR-0053): join rules not researched (a school, company or other body, or a library of unknown
 * kind). Such a library is searched only when the user added it; never joinable.
 */
export type Eligibility = 'OPEN' | 'ELIGIBLE' | 'UNKNOWN' | 'UNVERIFIED' | 'UNSURVEYED'
/** How far a library's join rules were researched (cycle 07, ADR-0053); picks the join notice of a candidate library. */
export type JoinSurvey = 'SURVEYED' | 'NOT_FOUND' | 'MEMBERS_ONLY' | 'NOT_SURVEYED'
/** Search priority group; the plan is ordered by it (design-delta "우선순위 그룹 계산"). */
export type PriorityGroup = 'MY_SIGUNGU' | 'MY_SIDO' | 'OPEN' | 'QUALIFIED' | 'NEEDS_CHECK'
export type LibraryType = 'EBOOK'
export type PlatformType =
  | 'KYOBO_V1'
  | 'KYOBO_SUBSCRIPTION_V1'
  | 'YES24_V1'
  | 'YES24_V2'
  | 'YES24_V3'
  | 'ELIB_PORTAL_V1'
  | 'GANGNAM_V1'
  | 'BOOKCUBE_FX'
  | 'ALADIN_V1'

/** RFC 9457 Problem Details with the `code` member (ADR-0008). Branch on `code`. */
export interface Problem {
  type?: string
  title: string
  status: number
  detail?: string
  instance?: string
  code: string
  errors?: Array<{ field: string; message?: string; code: string; value?: number }>
}

export interface SigunguNode {
  id: number
  name: string
}

export interface SidoNode {
  id: number
  name: string
  shortName: string | null
  children: SigunguNode[]
}

export type ResolutionStatus = 'ACTIVE' | 'REPLACED' | 'SPLIT' | 'UNKNOWN'

export interface RegionResolution {
  id: number
  status: ResolutionStatus
  label: string | null
  replacements: Array<{ id: number; label: string }>
}

/** One plan entry, in plan order. */
export interface TargetLibrary {
  id: number
  name: string
  libraryType: LibraryType
  /** Display only (e.g. "구독형"); screen logic never branches on it (ADR-0019). */
  platformType: PlatformType
  priorityGroup: PriorityGroup
  deferred: boolean
  eligibility: Eligibility
  eligibilitySummary: string | null
  /** Eligibility we cannot decide from regions, shown as text (e.g. 책이음 members). */
  extraConditionText: string | null
  visitRequired: boolean
  /** Null for a library whose join rules were not researched (cycle 07). */
  joinMethod: string | null
  /** The library's join or eligibility page; null when not known. */
  joinUrl: string | null
  /** Original text shown as a caution (e.g. loan cards only for residents); does not change the eligibility. */
  joinCaution: string | null
  /** Neutral information about who the library serves, shown as "안내"; no effect on eligibility or order. */
  joinNotice: string | null
  /** Front page of this row: the e-library's main page for EBOOK (a physical library's website later). */
  homepageUrl: string | null
  /**
   * The user added this library by hand (cycle 06, ADR-0050): first in the plan, kept by scope and visit, shown with
   * "직접 추가" instead of the eligibility badge. Only the first stream's `targets` carry it (C16).
   */
  chosen: boolean
  /** Cycle 07: how far the join rules were researched (SURVEYED for registered libraries). */
  joinSurvey: JoinSurvey
  /** Cycle 07 (ADR-0059): the library's own logo (`/library-logos/…`), or null: show no logo. */
  logoUrl: string | null
}

/** Which book field the search text is matched against; also the name of the one condition parameter (ADR-0025). */
export type SearchField = 'title' | 'author' | 'publisher'

/** The whole search plan, in plan order (first stream only). The text is in the member named like the condition. */
export interface TargetsEvent {
  title?: string
  author?: string
  publisher?: string
  filter: ResultFilter
  scope: LibraryScope
  visit: VisitFilter
  regionFilterApplied: boolean
  libraries: TargetLibrary[]
}

/** Which rows count toward a batch's row limit; the server gets it as `filter`. */
export type ResultFilter = 'all' | 'available'

/** Which libraries are searched: all, or only those the user can surely join (OPEN, ELIGIBLE, no extra condition). */
export type LibraryScope = 'all' | 'joinable'

/** "방문 없이 가입": `none` leaves visit-only libraries out of the plan. */
export type VisitFilter = 'any' | 'none'

/** Decided by the server per row (design-delta "대출 가능 여부 판정"). PENDING only appears in streams. */
export type Availability = 'AVAILABLE' | 'RESERVABLE' | 'PENDING' | 'UNKNOWN'
export type AvailabilityBasis = 'COUNT' | 'SITE_LIST' | 'LOANED_ZERO' | 'SITE_DETAIL' | 'SUBSCRIPTION'

export interface LibraryItem {
  rank: number
  title: string
  author: string | null
  publisher: string | null
  supplier: string | null
  /** null on screens without holdings (portal, subscription); `available` is null then too */
  holdings: number | null
  loaned: number | null
  reservations: number | null
  available: number | null
  /** Same key = same title when grouping rows (ADR-0018). Only compared, never shown. */
  titleKey: string
  availability: Availability
  availabilityBasis: AvailabilityBasis
  /** The book's own page on the library site; null when the site did not link one. */
  detailUrl: string | null
}

/** One page of one library. Also the data of the `library-result` event. */
export interface LibraryPage {
  libraryId: number
  query: string
  page: number
  pageSize: number
  totalCount: number
  hasMore: boolean
  fetchedAt: string
  cached: boolean
  cacheAgeSeconds: number
  loanRate: number | null
  librarySearchUrl: string
  items: LibraryItem[]
}

export interface LibraryRequestedEvent {
  batchNo: number
  libraryId: number
  page: number
}

export interface LibraryFailedEvent {
  batchNo: number
  libraryId: number
  page: number
  problem: Problem
}

export type StopReason = 'ROW_LIMIT' | 'CANDIDATES_EXHAUSTED' | 'LAUNCH_CUTOFF'

export interface BatchDoneEvent {
  batchNo: number
  requested: number
  succeeded: number
  failed: number
  countedRows: number
  pendingRows: number
  stopReason: StopReason
}

/** A PENDING row's final value; the row is found by libraryId + page + rank. */
export interface RowVerdictEvent {
  batchNo: number
  libraryId: number
  page: number
  rank: number
  availability: Availability
  availabilityBasis: AvailabilityBasis
}

export interface DoneEvent {
  batchNo: number
}

export type StreamEvent =
  | { name: 'targets'; data: TargetsEvent }
  | { name: 'library-requested'; data: LibraryRequestedEvent }
  | { name: 'library-result'; data: LibraryPage }
  | { name: 'library-failed'; data: LibraryFailedEvent }
  | { name: 'batch-done'; data: BatchDoneEvent }
  | { name: 'row-verdict'; data: RowVerdictEvent }
  | { name: 'done'; data: DoneEvent }

// ---- cycle 04: collected catalog search and holding streams (design-delta API) ----

/** First key of the book order (spec 3.5): the easiest plan library that holds the book. */
export type JoinTier = 'CHOSEN' | 'MY_SIGUNGU' | 'MY_SIDO' | 'OPEN' | 'VISIT_ONLY' | 'NEEDS_CHECK'

export type BookSort = 'default' | 'newest' | 'oldest'

/** Author roles that can be added to the author field; AUTHOR and unknown roles are always searched. */
export type BookRole = 'translator' | 'illustrator' | 'editor'

export interface BookItem {
  id: number
  title: string
  authorDisplay: string | null
  publisher: string | null
  /** YYYY-MM-DD; null when unknown (or only future dates were collected) */
  publishedOn: string | null
  /** Plan libraries (scope, visit, regions applied) that hold the book. */
  holdingLibraryCount: number
  joinTier: JoinTier
  exactTitle: boolean
}

/** `GET /api/v1/books`. */
export interface BookPage {
  items: BookItem[]
  page: number
  pageSize: number
  totalCount: number
  hasMore: boolean
  /** Collection dates (YYYY-MM-DD) of the plan libraries holding the result books; null without results. */
  collectedFrom: string | null
  collectedTo: string | null
}

/** `GET /api/v1/books/{bookId}`. */
export interface BookDetail {
  id: number
  title: string
  authorDisplay: string | null
  publisher: string | null
  publishedOn: string | null
  isbn13: string | null
  holdingLibraryCount: number
  /** Plan libraries holding the book, in plan order, with when their list was collected. */
  libraries: Array<{ libraryId: number; collectedAt: string | null }>
}

export interface HoldingRef {
  holdingId: number
  bookId: number
  libraryId: number
}

/** First holding stream: plan libraries that hold the books, and the holdings in book then plan order. */
export interface HoldingTargetsEvent {
  filter: ResultFilter
  scope: LibraryScope
  visit: VisitFilter
  regionFilterApplied: boolean
  libraries: TargetLibrary[]
  holdings: HoldingRef[]
}

export interface HoldingRequestedEvent extends HoldingRef {
  batchNo: number
}

/** `item.rank` is 0 and `item.titleKey` is `book:<id>`. */
export interface HoldingResultEvent extends HoldingRef {
  batchNo: number
  item: LibraryItem
  fetchedAt: string
  cached: boolean
}

export interface HoldingFailedEvent extends HoldingRef {
  batchNo: number
  problem: Problem
}

export type HoldingStreamEvent =
  | { name: 'targets'; data: HoldingTargetsEvent }
  | { name: 'holding-requested'; data: HoldingRequestedEvent }
  | { name: 'holding-result'; data: HoldingResultEvent }
  | { name: 'holding-failed'; data: HoldingFailedEvent }
  | { name: 'batch-done'; data: BatchDoneEvent }
  | { name: 'done'; data: DoneEvent }

/** A login provider (cycle 05). */
export type LoginProvider = 'NAVER' | 'KAKAO'

/** The documents agreed to at sign-up; AGE_OVER_14 has no document version. */
export type ConsentType = 'TERMS_OF_SERVICE' | 'PRIVACY_COLLECTION' | 'AGE_OVER_14'

/** `GET /api/v1/me`. `loginMethods` is never shown on screen (C23). */
export interface Me {
  id: number
  loginMethods: LoginProvider[]
  consents: Array<{ type: ConsentType; version?: string; agreedAt: string }>
}

/** `GET /api/v1/auth/signup`: the provider and the document versions the consent screen shows. */
export interface SignupInfo {
  provider: LoginProvider
  documents: Array<{ type: Exclude<ConsentType, 'AGE_OVER_14'>; version: string }>
}

/** One checked box sent with `POST /api/v1/auth/signup`. */
export interface ConsentInput {
  type: ConsentType
  version?: string
}

// ---- cycle 06: 내 도서관 (design-delta API, ADR-0049) ----

/** Which of the user's regions made a library ELIGIBLE. */
export type RegionBasis = 'RESIDENCE' | 'WORK'

/**
 * One row of `GET /api/v1/libraries`: every enabled library with the eligibility for the regions sent. Unlike the
 * search APIs, a library whose region rules do not match is here as UNKNOWN instead of being left out.
 */
export interface LibraryEligibilityRow {
  id: number
  name: string
  eligibility: Eligibility
  matchedBy: RegionBasis[]
  visitRequired: boolean
  /** The library has a join caution. */
  cautioned: boolean
  eligibilitySummary: string | null
  /** Cycle 07: how far the join rules were researched. */
  joinSurvey: JoinSurvey
  /** The e-library's main page, opened from the logo; may be null. */
  homepageUrl: string | null
  /** The library's own logo, or null (ADR-0059). */
  logoUrl: string | null
}

/** `GET`/`PUT /api/v1/me/regions`: saved ids in the chosen order (abolished ids come back as they are). */
export interface MyRegions {
  residenceRegionIds: number[]
  workRegionIds: number[]
}

/** `GET /api/v1/me/libraries`: added libraries, disabled ones included, oldest first. */
export interface MyLibraries {
  libraryIds: number[]
  max: number
}

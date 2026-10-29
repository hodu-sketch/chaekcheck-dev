// Response types. Field names match design-delta (camelCase).

/**
 * UNSURVEYED (cycle 07, ADR-0053): join rules not researched (a school, company or other body, or a library of unknown
 * kind). Such a library is searched only when the user added it; never joinable.
 */
export type Eligibility = 'OPEN' | 'ELIGIBLE' | 'UNKNOWN' | 'UNVERIFIED' | 'UNSURVEYED'
/** How far a library's join rules were researched (cycle 07, ADR-0053); picks the join notice of a library found in the collected list. */
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
  | 'SEOUL_ELIB_V1'
/** Cycle 09: the company that runs a library's e-book site, shown next to the name ("교보 · 소장"). */
export type Vendor = 'KYOBO' | 'YES24' | 'BOOKCUBE' | 'ALADIN' | 'PORTAL' | 'IN_HOUSE'
/** Cycle 09: how a site lends its books; null only for a portal. */
export type LendingModel = 'OWNED' | 'SUBSCRIPTION' | 'MIXED' | 'RENTAL'

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
  /**
   * The user has to visit the library to join, for the regions of this search: a visit-only library, or a
   * `visitOutsideRegion` one when the regions do not match its own (then the group is OPEN).
   */
  visitRequired: boolean
  /**
   * Residents of the library's region join online, everyone else only by a visit (user 2026-10-20 "지역 외는 방문
   * 가입"); with `visitRequired` the row shows 「지역 외는 방문 가입」. Missing while the server is older.
   */
  visitOutsideRegion?: boolean
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
  /** Cycle 08 (R3): the site's phone version shows another page; phones get a note, the links stay. */
  mobileSiteDiffers: boolean
  /** Cycle 09: the classification shown with the name; may be missing while the server is older (deploy order). */
  vendor?: Vendor | null
  lendingModel?: LendingModel | null
  /** Cycle 09: 1, or 2 and up for a second site of the same library and vendor ("· 2번째 사이트"). */
  orderNo?: number
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
  /** The edition's cover on CloudFront (cycle 26), or null. */
  coverUrl: string | null
  /** The 책첵 summary (cycle 26), never cut, or null. */
  summary: string | null
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
  /** The book's page at the library, so the row still links there (user request 2026-10-18); none from an older
   * server, null when the page may not be opened or the holding is gone. */
  detailUrl?: string | null
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
  /** A visit is needed for the regions sent (as `TargetLibrary.visitRequired`). */
  visitRequired: boolean
  /** As `TargetLibrary.visitOutsideRegion` (user 2026-10-20); missing while the server is older. */
  visitOutsideRegion?: boolean
  /** The library has a join caution. */
  cautioned: boolean
  eligibilitySummary: string | null
  /** Cycle 07: how far the join rules were researched. */
  joinSurvey: JoinSurvey
  /** The e-library's main page, opened from the logo; may be null. */
  homepageUrl: string | null
  /** The library's own logo, or null (ADR-0059). */
  logoUrl: string | null
  /** Cycle 08 (R3): the site's phone version shows another page. */
  mobileSiteDiffers: boolean
  /** Cycle 09: the classification shown with the name; may be missing while the server is older (deploy order). */
  vendor?: Vendor | null
  lendingModel?: LendingModel | null
  /** Cycle 09: 1, or 2 and up for a second site of the same library and vendor ("· 2번째 사이트"). */
  orderNo?: number
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

// ---- cycle 14: librarian picks; cycle 21: the three recommended lists (ADR-0126) ----

/** The recommended lists, as `GET /api/v1/picks/{listKey}` names them. */
export type PicksListKey = 'librarian' | 'leedongjin' | 'snu100' | 'kaist' | 'nobel'

/** One book of `GET /api/v1/picks/{listKey}`. */
export interface PickBook {
  /** The librarian's recommendation number (1 to 20 digits); the other lists' row id. Unique within a list. */
  id: string
  /** The book's field code ("11", "6", ... for the librarian; "foreign_fiction", "korean_lit", ... for the others). */
  category: string
  /** The shown name: our book's title without its edition marks, else the list's title (ADR-0129). */
  title: string
  /** The list's own title when it differs from `title` (S14 shows it on one line), else null. */
  listTitle: string | null
  /**
   * The cover's CloudFront address `<base>/covers/<sha1>.jpg` (cycle 26, ADR-0163), or null: the card shows the title
   * instead (AC-14b).
   */
  coverUrl: string | null
  /** Our book, also when no plan library holds it; null when none. */
  bookId: number | null
  /** `bookId` is set and a plan library holds the book; false shows the cover faded (AC-6). */
  available: boolean
  /** "YYYY-MM": the librarian's and Lee Dong-jin's recommendation month; null for the SNU 100. */
  recommendedMonth: string | null
  /** Lee Dong-jin: the year of the books of the year it was in, else null. */
  yearBook: number | null
  /** Lee Dong-jin's video, else null. */
  videoId: string | null
  /** The SNU 100's table-of-contents number, else null. */
  tocNo: number | null
  /** A short AI-written summary of the book (S14 shows it under 「AI 요약 ⓘ」), else null. Absent from older servers. */
  aiSummary?: string | null
  /** the field's group (literature, humanities, …), kept with the books this browser looked at; null when unknown */
  fieldGroup?: string | null
}

/** One field in display order: `ALL` first, then the list's fields. `name` is the name shown. */
export interface PickCategory {
  code: string
  name: string
  books: PickBook[]
}

/** `GET /api/v1/picks/{listKey}`: no categories before the list is stored. */
export interface Picks {
  list: PicksListKey
  /** The librarian picks' latest successful fetch, ISO date-time with offset; null for the other lists. */
  fetchedAt: string | null
  categories: PickCategory[]
}

/** `GET /api/v1/picks/shelf`: one book per field, in the fields' order. */
export interface PicksShelf {
  list: PicksListKey
  books: PickBook[]
}

// ---- cycle 13: join survey date (ADR-0093) ----

/** `GET /api/v1/join-surveys/latest`: the newest join survey date of the library inserts; null before the first. */
export interface LatestJoinSurvey {
  /** "YYYY-MM-DD" */
  surveyedOn: string | null
}

// ---- cycle 16: notices and documents (design-delta API, ADR-0098) ----

/** One row of `GET /api/v1/notices`. The list never says when a notice was edited (AC-13). */
export interface NoticeListItem {
  id: number
  title: string
  /** "YYYY-MM-DD": the effective date of version 1 */
  postedOn: string
}

/** `GET /api/v1/notices?page=n`: 10 a page; `pinned` only on page 1 and not counted. `page` is the page the server used. */
export interface NoticeList {
  pinned: NoticeListItem[]
  items: NoticeListItem[]
  page: number
  pageSize: number
  totalCount: number
  hasMore: boolean
}

/** One version in `versions`: in force or past ones only (future ones are left out). */
export interface DocumentVersionInfo {
  number: number
  /** "YYYY-MM-DD" */
  effectiveOn: string
  /** "YYYY-MM-DD" the next version took over; null for the current version */
  supersededOn: string | null
}

/** The version being shown. */
export interface ShownVersion {
  number: number
  effectiveOn: string
  /** false for an earlier version opened with `?version=` */
  current: boolean
  /** Markdown (C2: drawn without HTML) */
  body: string
}

/** `GET /api/v1/notices/{noticeId}?version=n` */
export interface Notice {
  id: number
  title: string
  postedOn: string
  /** the current version's effective date when the notice was edited, else null */
  editedOn: string | null
  version: ShownVersion
  versions: DocumentVersionInfo[]
}

/** The address keys of `GET /api/v1/documents/{documentKey}`. */
export type DocumentKey = 'about' | 'terms-of-service' | 'privacy-policy' | 'privacy-collection'

/** One of the four things a collection consent must say (개인정보 보호법 제15조②). */
export interface ConsentItem {
  label: string
  text: string
}

/** `GET /api/v1/documents/{documentKey}?version=n` */
export interface SiteDocument {
  key: DocumentKey
  version: ShownVersion & {
    /** Markdown, only on terms, privacy policy and collection consent versions that changed something */
    changeSummary?: string | null
    /** exactly four on `privacy-collection`, absent or null elsewhere */
    consentItems?: ConsentItem[] | null
  }
  versions: DocumentVersionInfo[]
}

/** The 404 codes of the notice and document APIs. */
export type SiteContentNotFound = 'notice_not_found' | 'document_not_found' | 'version_not_found'

// ---- cycle 23: 「나에게 맞는 책」 (design-delta API, ADR-0149) ----

/** One tag on a for-you card (R9-5): shown, not pressable. */
export interface ForYouTag {
  code: string
  axis: 'mood' | 'subject' | 'audience'
  name: string
}

/** One for-you card. Scores and source names are never sent (R3-2). */
export interface ForYouCard {
  bookId: number
  title: string
  author: string | null
  publisher: string | null
  /** The cover's CloudFront address `<base>/covers/<sha1>.jpg`, or null for the title-letter cover */
  coverUrl: string | null
  /** the 책첵 summary (cycle 26; the lists' book window shows the same one), or null */
  blurb: string | null
  /** 2-3 tags of a listed book, else empty */
  tags: ForYouTag[]
  /** how many plan libraries hold the book (≥ 1) */
  planLibraryCount: number
  /** the recommended lists the book is on (keys); the screen writes the tab names */
  lists: PicksListKey[]
  /** the first author's key, for "no three of one author in a row" (AC-8), or null */
  authorKey: string | null
  /** `YYYY-MM`, the 국립중앙도서관 list's month for S14's year line, else null */
  recommendedMonth?: string | null
  /** the KAIST list's year for S14's year line, else null */
  yearBook?: number | null
  /** literature, humanities, society, science, arts or other for a listed book, else null (other) */
  fieldGroup?: string | null
}

/** `GET /api/v1/for-you/books`: `builtOn` null before the first build (then the listed books only). */
export interface ForYouBooks {
  builtOn: string | null
  books: ForYouCard[]
}

/** `GET /api/v1/for-you/similar-books`. */
export interface ForYouSimilar {
  baseBooks: { bookId: number; title: string }[]
  books: ForYouCard[]
  reshowBooks: ForYouCard[]
}

/**
 * The header menus (cycle 21 spec R1, ADR-0127): three plain links, no sub-menus (user 2026-10-19). The picks lists
 * (이동진/사서/서울대) are chosen inside the picks page; 내 도서관 and 계정 are one 마이페이지 at /my-libraries.
 */
export const MENUS = [
  { key: 'search', label: '도서검색', to: '/search' },
  { key: 'picks', label: '추천도서', to: '/picks' },
  { key: 'my', label: '마이페이지', to: '/my-libraries' },
] as const

export type Menu = (typeof MENUS)[number]

/**
 * 책첵이란? (the static /about page): a quiet grey link right next to the logo on every width, out of the menus and
 * the phone menu (user 2026-10-19: "좀 더 회색으로 해서 좌상단 책첵 로고 바로 오른쪽에 오도록").
 */
// the header link reads 「소개」 (user 2026-10-20: "좌상단 책첵이란? 말고 '소개'로"); the page keeps its 책첵이란? title
export const ABOUT_LINK = { label: '소개', to: '/about' } as const

/** Whether the screen at {@code pathname} belongs to the menu (underlined). */
export function inMenu(pathname: string, menu: Menu) {
  switch (menu.key) {
    // not the main page: nothing is marked there (user 2026-10-20: "메인에서는 상단에 도서검색 보라색글자랑 보라색 줄 없이")
    case 'search':
      return pathname === '/search' || pathname.startsWith('/books/')
    case 'picks':
      return pathname === '/picks' || pathname.startsWith('/picks/')
    case 'my':
      return pathname === '/my-libraries' || pathname === '/account'
  }
}

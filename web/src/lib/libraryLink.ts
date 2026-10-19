import { useMediaQuery } from './useMediaQuery'

/** Touch screens: the main pointer is a finger (`.claude/rules/web/patterns.md` Touch Detection; not `hover: none`). */
export const TOUCH_QUERY = '(pointer: coarse)'

/**
 * How a link to a library's site opens (user 2026-10-19: "모바일은 새 탭으로 가게 되어서 사용자가 혼란스러우니까 새 탭으로
 * 안 가도록"): on touch screens in the same tab, on a computer in a new tab as before. Spread `props` on the `<a>`;
 * `newTab` says whether to add the screen-reader "(새 창)".
 */
export function useLibraryLink(): { newTab: boolean; props: { target?: '_blank'; rel: string } } {
  const touch = useMediaQuery(TOUCH_QUERY)
  return touch
    ? { newTab: false, props: { rel: 'noopener noreferrer' } }
    : { newTab: true, props: { target: '_blank', rel: 'noopener noreferrer' } }
}

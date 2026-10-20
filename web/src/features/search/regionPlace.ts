import { useRegionLabels, useRegionTree } from '../region/useRegionTree'

/** "로" after a vowel or ㄹ, else "으로" (서울 강서구로, 성남시로, … 외 2곳으로, 재직으로). */
export function ro(word: string): string {
  const code = word.charCodeAt(word.length - 1) - 0xac00
  if (code < 0 || code > 11171) return '(으)로'
  const final = code % 28
  return final === 0 || final === 8 ? '로' : '으로'
}

/**
 * A short name of some regions (cycle 22 문구표, like the folded line of step 3): "서울 강남구", two or three of one sido
 * "서울 강남구·서초구", of several sidos "서울 강남구·경기 성남시", four or more "서울 강남구 외 3곳". Null for none, or while
 * the region names are not known yet.
 */
export function regionPlaceText(ids: readonly number[], labels: ReadonlyMap<number, string>): string | null {
  const names = ids.map((id) => labels.get(id)).filter((name): name is string => name !== undefined)
  if (names.length === 0 || names.length < ids.length) return null
  if (names.length >= 4) return `${names[0]} 외 ${names.length - 1}곳`
  const sido = (name: string) => name.split(' ')[0]
  if (names.every((name) => sido(name) === sido(names[0]) && name.includes(' '))) {
    return [names[0], ...names.slice(1).map((name) => name.slice(sido(name).length + 1))].join('·')
  }
  return names.join('·')
}

/** {@link regionPlaceText} from the region tree (fetched once per visit). */
export function useRegionPlace(ids: readonly number[]): string | null {
  const tree = useRegionTree()
  const labels = useRegionLabels(tree.data)
  return regionPlaceText(ids, labels)
}

/**
 * The regions of the 「내 도서관」 group's small line, 거주 first then 직장: "서울 구로구", or "서울 구로구 외 N곳" with
 * N 1 and up (user 2026-10-20: "직장도 정보 넣긴 했거든", "외 N곳 표기로 줄이고"); null for none or while the names load.
 */
export function useMineLabel(residence: readonly number[], work: readonly number[]): string | null {
  const labels = useRegionLabels(useRegionTree().data)
  const ids = [...new Set([...residence, ...work])]
  const first = ids.length > 0 ? labels.get(ids[0]) : undefined
  if (first === undefined) return null
  return ids.length > 1 ? `${first} 외 ${ids.length - 1}곳` : first
}

/** The place words of the result tags (handoff 5-A-5): the one 시군구 and the one 시도 the search's regions name, if so. */
export interface TagPlace {
  sigungu: string | null
  sido: string | null
}

/**
 * {@link TagPlace} for the regions a search was made with: the 시군구 name when they are one 시군구, the 시도 short
 * name when they are all in one 시도; null where they differ (a library of "my 시군구" cannot say which one).
 */
export function tagPlaceOf(ids: readonly number[], labels: ReadonlyMap<number, string>): TagPlace {
  const names = ids.map((id) => labels.get(id)).filter((name): name is string => name !== undefined)
  if (names.length === 0 || names.length < ids.length) return { sigungu: null, sido: null }
  const sidos = new Set(names.map((name) => name.split(' ')[0]))
  const sido = sidos.size === 1 ? names[0].split(' ')[0] : null
  const unique = new Set(names)
  const only = unique.size === 1 ? names[0] : null
  // "서울 강서구" → "강서구"; 세종 has no 시군구 of its own and is named alone
  const sigungu = only === null ? null : only.includes(' ') ? only.slice(only.indexOf(' ') + 1) : only
  return { sigungu, sido }
}

/** {@link tagPlaceOf} from the region tree. */
export function useTagPlace(ids: readonly number[]): TagPlace {
  const tree = useRegionTree()
  const labels = useRegionLabels(tree.data)
  return tagPlaceOf(ids, labels)
}

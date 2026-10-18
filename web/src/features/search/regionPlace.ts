import { useRegionLabels, useRegionTree } from '../region/useRegionTree'

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

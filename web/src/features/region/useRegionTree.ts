import { useQuery } from '@tanstack/react-query'
import { useMemo } from 'react'
import { fetchRegions } from '../../api/client'
import type { SidoNode } from '../../api/types'

/** SIDO short names shown differently from the server's (user 2026-10-19: "전남광주 전남으로 바꾸고"). */
const SHORT_NAME_SHOWN: Record<string, string> = { 전남광주: '전남' }

/**
 * What the screens show of the tree: the short names above, and each SIDO's SIGUNGU in 가나다 order (user
 * 2026-10-19: "두번째단계는 가나다순으로"). The ids and the SIDO order stay as the server sends them.
 */
export function shownRegionTree(tree: SidoNode[]): SidoNode[] {
  return tree.map((sido) => ({
    ...sido,
    shortName: sido.shortName === null ? null : (SHORT_NAME_SHOWN[sido.shortName] ?? sido.shortName),
    children: [...sido.children].sort((a, b) => a.name.localeCompare(b.name, 'ko')),
  }))
}

/** The active region tree. Changes only with a server deploy, so it is fetched once per visit. */
export function useRegionTree() {
  return useQuery({
    queryKey: ['regions'],
    queryFn: ({ signal }) => fetchRegions(signal),
    select: shownRegionTree,
    staleTime: Infinity,
    gcTime: Infinity,
  })
}

/**
 * SIGUNGU id -> "서울 마포구" (SIDO short name when there is one). A SIGUNGU named like its SIDO
 * (세종특별자치시, which has no SIGUNGU of its own) is shown by that name alone.
 */
export function useRegionLabels(tree: SidoNode[] | undefined): Map<number, string> {
  return useMemo(() => {
    const labels = new Map<number, string>()
    for (const sido of tree ?? []) {
      const prefix = sido.shortName ?? sido.name
      for (const sigungu of sido.children) {
        labels.set(sigungu.id, sigungu.name === sido.name ? sido.name : `${prefix} ${sigungu.name}`)
      }
    }
    return labels
  }, [tree])
}

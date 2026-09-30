import { useQuery } from '@tanstack/react-query'
import { useMemo } from 'react'
import { fetchRegions } from '../../api/client'
import type { SidoNode } from '../../api/types'

/** The active region tree. Changes only with a server deploy, so it is fetched once per visit. */
export function useRegionTree() {
  return useQuery({
    queryKey: ['regions'],
    queryFn: ({ signal }) => fetchRegions(signal),
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

import { useQuery } from '@tanstack/react-query'
import { useMemo } from 'react'
import { fetchRegions } from '../../api/client'
import type { SidoNode } from '../../api/types'

/** SIDO short names shown differently from the server's (user 2026-10-19: "전남광주 전남으로 바꾸고"). */
const SHORT_NAME_SHOWN: Record<string, string> = { 전남광주: '전남' }

/** A server 시도 short name as the screens show it (전남광주 → 전남). */
export function shownSidoName(shortName: string): string {
  return SHORT_NAME_SHOWN[shortName] ?? shortName
}

/**
 * The 시군구 of the former 광주광역시, now in 전남광주통합특별시, keep 「광주」 in front: people still say 광주 남구, not 전남
 * 남구 (user 2026-10-20: "사람들이 이게 아직 편해서"). scripts/web/gen_start_examples.py writes its pills the same way.
 */
const FORMER_GWANGJU = new Set(['동구', '서구', '남구', '북구', '광산구'])
const MERGED_SIDO = '전남광주'

/** The 시도 word in front of a 시군구 as the screens show it: 광주 for the former 광주 구, else {@link shownSidoName}. */
export function shownPrefix(serverShortName: string, sigungu: string): string {
  if (serverShortName === MERGED_SIDO && FORMER_GWANGJU.has(sigungu)) return '광주'
  return shownSidoName(serverShortName)
}

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
        // 광주 남구, not 전남 남구 (FORMER_GWANGJU); the tree here already shows 전남광주 as 전남
        const shown = prefix === shownSidoName(MERGED_SIDO) && FORMER_GWANGJU.has(sigungu.name) ? '광주' : prefix
        labels.set(sigungu.id, sigungu.name === sido.name ? sido.name : `${shown} ${sigungu.name}`)
      }
    }
    return labels
  }, [tree])
}

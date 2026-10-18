import { useQuery } from '@tanstack/react-query'
import { fetchLibraries } from '../../api/client'

/**
 * The whole library list without regions (`GET /api/v1/libraries`), for names: the box's added libraries and the
 * 상세검색 name search (cycle 22 D4). Asked only when `enabled`, then kept in the page for 5 minutes under the key
 * `['libraries', '', '']` (the my page's key for "no regions", so the two share it).
 */
export function useLibraryList(enabled: boolean) {
  return useQuery({
    queryKey: ['libraries', '', ''],
    queryFn: ({ signal }) => fetchLibraries([], [], signal),
    enabled,
    staleTime: 5 * 60_000,
  })
}

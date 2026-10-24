import { useQuery } from '@tanstack/react-query'
import { fetchAiStatus } from '../../api/aiSearch'

/**
 * Whether the AI search is on (cycle 24 step 0 #9 [안]): asked once per page load. Until the answer comes (and when
 * the question fails) the AI tab stays; a 404 hides it.
 */
export function useAiStatus(): 'on' | 'off' | 'unknown' {
  const status = useQuery({ queryKey: ['ai-search-status'], queryFn: fetchAiStatus, staleTime: Infinity, retry: false })
  return status.data ?? 'unknown'
}

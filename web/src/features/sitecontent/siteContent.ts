import { useQuery } from '@tanstack/react-query'
import { useSearchParams } from 'react-router'
import { fetchDocument, retryServerErrors } from '../../api/client'
import type { DocumentKey } from '../../api/types'

/** A positive whole number written plainly ("1", "12"), like the server's `version` and `page` rules (C5). */
export function positiveNumber(raw: string | null): number | null {
  return raw !== null && /^[1-9][0-9]{0,8}$/.test(raw) ? Number(raw) : null
}

/**
 * The `?version=n` of the address: null for none (the current version), 'invalid' for one that is not a positive
 * whole number. An invalid one is shown as "찾을 수 없어요" without asking the server.
 */
export function useVersionParam(): number | null | 'invalid' {
  const [params] = useSearchParams()
  const raw = params.get('version')
  if (raw === null) return null
  return positiveNumber(raw) ?? 'invalid'
}

/** The document at the address's version (S8, S9, S12). Not asked while the version is invalid. */
export function useSiteDocument(key: DocumentKey) {
  const version = useVersionParam()
  const query = useQuery({
    queryKey: ['documents', key, version],
    queryFn: ({ signal }) => fetchDocument(key, version === 'invalid' ? null : version, signal),
    enabled: version !== 'invalid',
    retry: retryServerErrors,
  })
  return { version, query }
}

import type { UseQueryResult } from '@tanstack/react-query'
import type { ReactNode } from 'react'
import { useLocation } from 'react-router'
import { notFoundCode } from '../../api/client'
import type { SiteDocument } from '../../api/types'
import { LoadFailed, LoadingLines, NotFound } from '../../components/ContentStates'

/**
 * The part of S8·S9·S12 under the title: grey lines while loading, "찾을 수 없어요" with a link to the current version
 * for a version that does not exist (AC-9), "글을 불러오지 못했어요" with 다시 시도 otherwise, else `children(doc)`.
 */
export function DocumentState({
  version,
  query,
  children,
}: {
  version: number | null | 'invalid'
  query: UseQueryResult<SiteDocument>
  children: (doc: SiteDocument) => ReactNode
}) {
  const { pathname } = useLocation()
  if (version === 'invalid' || (query.isError && notFoundCode(query.error) === 'version_not_found')) {
    return <NotFound to={pathname} label="지금 버전 보기" />
  }
  if (query.isPending) return <LoadingLines />
  if (query.isError) return <LoadFailed onRetry={() => void query.refetch()} />
  return children(query.data)
}

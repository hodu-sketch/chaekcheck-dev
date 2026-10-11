import type { LendingModel, Vendor } from '../api/types'

/** Cycle 09 handoff step 5-2 [확정]: the vendor as shown next to a library's name. */
export const VENDOR_LABEL: Record<Vendor, string> = {
  KYOBO: '교보',
  YES24: 'YES24',
  BOOKCUBE: '북큐브',
  ALADIN: '알라딘',
  PORTAL: '통합 포털',
  IN_HOUSE: '자체',
}

/** Cycle 09 handoff step 5-2 [확정]: how the site lends its books. */
export const LENDING_LABEL: Record<LendingModel, string> = {
  OWNED: '소장',
  SUBSCRIPTION: '구독',
  MIXED: '혼합',
  RENTAL: '대여',
}

/** The fields of a library row the classification is built from; all may be missing (deploy order). */
export interface Classified {
  vendor?: Vendor | null
  lendingModel?: LendingModel | null
  orderNo?: number
}

/**
 * The parts of a library's classification: ["교보", "소장"], ["통합 포털"], with "N번째 사이트" for a second site of the
 * same library (orderNo 2 and up). Null without a vendor: the name only (an older server). A non-portal row without
 * its lending model shows the vendor only [안] (the DB check keeps it from happening).
 */
export function classificationParts(row: Classified): string[] | null {
  if (!row.vendor) return null
  const parts = [VENDOR_LABEL[row.vendor]]
  if (row.vendor !== 'PORTAL' && row.lendingModel) parts.push(LENDING_LABEL[row.lendingModel])
  if (row.orderNo !== undefined && row.orderNo >= 2) parts.push(`${row.orderNo}번째 사이트`)
  return parts
}

/** "교보 · 소장", "통합 포털", "YES24 · 소장 · 2번째 사이트", or null (name only). */
export function classificationText(row: Classified): string | null {
  return classificationParts(row)?.join(' · ') ?? null
}

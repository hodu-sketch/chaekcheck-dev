import type { Availability, AvailabilityBasis } from '../../api/types'
import type { ResultRow } from './sortBatch'

/** A result row for tests; override what matters. */
export function testRow(overrides: Partial<ResultRow> & { libraryId: number; rank: number }): ResultRow {
  const availability: Availability = overrides.availability ?? 'AVAILABLE'
  const basis: AvailabilityBasis = overrides.availabilityBasis ?? 'COUNT'
  return {
    page: 1,
    title: `title-${overrides.libraryId}-${overrides.rank}`,
    titleKey: `key-${overrides.libraryId}-${overrides.rank}`,
    author: null,
    publisher: null,
    supplier: null,
    holdings: 1,
    loaned: 0,
    reservations: 0,
    available: 1,
    detailUrl: null,
    ...overrides,
    availability,
    availabilityBasis: basis,
  }
}

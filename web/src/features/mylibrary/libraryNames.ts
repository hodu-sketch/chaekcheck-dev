import { LENDING_LABEL, VENDOR_LABEL, type Classified } from '../../lib/libraryClassification'

/**
 * Name search on the 내 도서관 screen [안]: NFC, spaces and middle dots removed, case ignored; a part of the name
 * matches. The dots (cycle 08 [안]): "한국형사·법무정책연구원" is found with "형사법무" too.
 */
export function nameKey(name: string): string {
  return name.normalize('NFC').replace(/[\s·・]+/g, '').toLowerCase()
}

/** Typed characters before the name search starts (cycle 07 flow S10) [확정]. */
export const MIN_NAME_QUERY = 2

const KOREAN = new Intl.Collator('ko')

/**
 * The name search of S10 (cycle 07 flow S10, cycle 09 handoff step 5-5) [확정]: from two typed characters (spaces
 * ignored). A row matches when every word of the query is in its name or in its classification ("교보소장"), or when
 * the whole query is in its name ("창원대학교거창", as before). Names starting with the query first, then 가나다 order,
 * the same name by vendor ("군산시립" gives 교보, 북큐브, YES24). A row with no classification matches by name only.
 */
export function findByName<T extends { name: string } & Classified>(rows: T[], query: string): T[] {
  const key = nameKey(query)
  if ([...key].length < MIN_NAME_QUERY) return []
  const words = query.split(/\s+/).map(nameKey).filter(Boolean)
  return rows
    .map((row) => {
      const vendor = row.vendor ? VENDOR_LABEL[row.vendor] : ''
      const lending = row.lendingModel ? LENDING_LABEL[row.lendingModel] : ''
      return { row, name: nameKey(row.name), vendor, classification: nameKey(vendor + lending) }
    })
    .filter(
      ({ name, classification }) =>
        name.includes(key) ||
        words.every((word) => name.includes(word) || classification.includes(word)),
    )
    .sort(
      (a, b) =>
        Number(b.name.startsWith(key)) - Number(a.name.startsWith(key)) ||
        KOREAN.compare(a.row.name, b.row.name) ||
        KOREAN.compare(a.vendor, b.vendor) ||
        (a.row.orderNo ?? 1) - (b.row.orderNo ?? 1),
    )
    .map(({ row }) => row)
}

/** Name search on the 내 도서관 screen [안]: NFC, spaces removed, case ignored; a part of the name matches. */
export function nameKey(name: string): string {
  return name.normalize('NFC').replace(/\s+/g, '').toLowerCase()
}

/** Typed characters before the name search starts (cycle 07 flow S10) [확정]. */
export const MIN_NAME_QUERY = 2

const KOREAN = new Intl.Collator('ko')

/**
 * The name search of S10 (cycle 07 flow S10) [확정]: from two typed characters, names containing them (spaces
 * ignored), the ones that start with them first, then in 가나다 order.
 */
export function findByName<T extends { name: string }>(rows: T[], query: string): T[] {
  const key = nameKey(query)
  if ([...key].length < MIN_NAME_QUERY) return []
  return rows
    .map((row) => ({ row, name: nameKey(row.name) }))
    .filter(({ name }) => name.includes(key))
    .sort((a, b) => Number(b.name.startsWith(key)) - Number(a.name.startsWith(key)) || KOREAN.compare(a.row.name, b.row.name))
    .map(({ row }) => row)
}

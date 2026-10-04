/** Name search on the 내 도서관 screen [안]: NFC, spaces removed, case ignored; a part of the name matches. */
export function nameKey(name: string): string {
  return name.normalize('NFC').replace(/\s+/g, '').toLowerCase()
}

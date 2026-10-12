/** "2026-10-05" → "2026년 10월", read from the string itself (no `Date`, so no time-zone shift); null if not a date. */
export function surveyMonthLabel(surveyedOn: string): string | null {
  const [year, month] = surveyedOn.split('-')
  const monthNumber = Number(month)
  if (!/^\d{4}$/.test(year ?? '') || !Number.isInteger(monthNumber) || monthNumber < 1 || monthNumber > 12) return null
  return `${year}년 ${monthNumber}월`
}

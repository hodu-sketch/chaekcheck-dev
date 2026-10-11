/**
 * Small line icons drawn as SVG so they sit exactly in the middle of their buttons (cycle 10 icon check, user
 * 2026-10-12: "다른 아이콘들도 싹 다 점검해 봐"). Text glyphs (▾ ✓ × ☰ ‹ >>) sat 1-2px off and changed size by font.
 * All are decorative (`aria-hidden`); the button around them carries the name. The chevron is the one the sort menu
 * already used (12 x 8, 2px stroke).
 */
type Props = { size?: number; className?: string }

const common = {
  fill: 'none',
  stroke: 'currentColor',
  strokeWidth: 2,
  strokeLinecap: 'round' as const,
  strokeLinejoin: 'round' as const,
  'aria-hidden': true,
  focusable: false,
}

export function ChevronDown({ size = 12, className }: Props) {
  return (
    <svg className={className} width={size} height={(size * 8) / 12} viewBox="0 0 12 8" {...common}>
      <path d="M1 1.5l5 5 5-5" />
    </svg>
  )
}

export function ChevronUp({ size = 12, className }: Props) {
  return (
    <svg className={className} width={size} height={(size * 8) / 12} viewBox="0 0 12 8" {...common}>
      <path d="M1 6.5l5-5 5 5" />
    </svg>
  )
}

export function ChevronLeft({ size = 8, className }: Props) {
  return (
    <svg className={className} width={size} height={(size * 12) / 8} viewBox="0 0 8 12" {...common}>
      <path d="M6.5 1l-5 5 5 5" />
    </svg>
  )
}

export function ChevronRight({ size = 8, className }: Props) {
  return (
    <svg className={className} width={size} height={(size * 12) / 8} viewBox="0 0 8 12" {...common}>
      <path d="M1.5 1l5 5-5 5" />
    </svg>
  )
}

export function CheckMark({ size = 12, className }: Props) {
  return (
    <svg className={className} width={size} height={size} viewBox="0 0 12 12" {...common}>
      <path d="M1.5 6.5l3 3 6-7" />
    </svg>
  )
}

export function Cross({ size = 12, className }: Props) {
  return (
    <svg className={className} width={size} height={size} viewBox="0 0 12 12" {...common}>
      <path d="M2 2l8 8M10 2l-8 8" />
    </svg>
  )
}

export function MenuLines({ size = 18, className }: Props) {
  return (
    <svg className={className} width={size} height={size} viewBox="0 0 18 18" {...common}>
      <path d="M3 5h12M3 9h12M3 13h12" />
    </svg>
  )
}

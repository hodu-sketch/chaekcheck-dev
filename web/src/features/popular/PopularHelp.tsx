import { useState } from 'react'
import { BottomSheet } from '../../components/BottomSheet'
import { InfoTip } from '../../components/InfoTip'
import { fetchedLabel, HELP_LINES, monthLabel, SOURCE_TEXT } from './popularText'
import styles from './PopularPage.module.css'

type Props = {
  month: string | null
  fetchedAt: string | null
  phone: boolean
}

/**
 * S13i (R7-1, R4-5): the source and the stores it adds up, the month and the fetch day, and how to read the faded
 * covers. PC and tablet: the small ⓘ bubble used elsewhere (`InfoTip`); phones: the bottom sheet with ⓧ.
 */
export function PopularHelp({ month, fetchedAt, phone }: Props) {
  const [open, setOpen] = useState(false)
  const when = [monthLabel(month) && `${monthLabel(month)} 판매 순위`, fetchedLabel(fetchedAt)].filter(Boolean).join(' · ')
  const lines = (
    <ul className={styles.helpList}>
      <li>{SOURCE_TEXT}</li>
      {when && <li>{when}</li>}
      {HELP_LINES.map((line) => (
        <li key={line}>{line}</li>
      ))}
    </ul>
  )
  if (!phone) return <InfoTip label="인기 도서 안내" text={lines} />
  return (
    <>
      <button
        type="button"
        className={styles.helpButton}
        aria-label="인기 도서 안내"
        aria-haspopup="dialog"
        aria-expanded={open}
        onClick={() => setOpen(true)}
      >
        ⓘ
      </button>
      <BottomSheet open={open} onOpenChange={setOpen} title="인기 도서 안내" closeButton closeOnBack>
        <div className={styles.helpSheet}>{lines}</div>
      </BottomSheet>
    </>
  )
}

import type { MouseEvent } from 'react'
import { sendUsageEvent, type ClickPlace, type ClickScreen } from '../api/usageEvents'

/** Where a result line is shown, and its book when the screen has one (cycle 15 usage log, spec R2-2). */
export interface ClickContext {
  screen: ClickScreen
  bookId?: number
}

/**
 * Click handlers for a library link: the report goes out on the same click and the link opens its new tab as before
 * (nothing is prevented). A middle-button click opens a tab too, so it counts; a long press or the context menu does
 * not.
 */
export function libraryClick(libraryId: number | undefined, place: ClickPlace, context: ClickContext | undefined) {
  if (libraryId === undefined || context === undefined) return {}
  const report = () =>
    sendUsageEvent({
      type: 'library_click',
      libraryId,
      place,
      screen: context.screen,
      ...(context.bookId !== undefined ? { bookId: context.bookId } : {}),
    })
  return {
    onClick: () => report(),
    onAuxClick: (event: MouseEvent) => {
      if (event.button === 1) report()
    },
  }
}

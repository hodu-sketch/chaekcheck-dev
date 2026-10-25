import type { MouseEvent } from 'react'
import { sendUsageEvent, type ClickPlace, type ClickScreen } from '../api/usageEvents'
import { recordLibraryClicked } from '../features/picks/viewedBooks'

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
  const report = () => {
    // 「나에게 맞는 책」 (cycle 23 R5-3): a library line pressed on the book screen or in a picks book window counts for
    // the book in this browser only; search results do not count
    if (place === 'result_card' && context.bookId !== undefined && (context.screen === 'book_page' || context.screen === 'picks')) {
      recordLibraryClicked(context.bookId)
    }
    sendUsageEvent({
      type: 'library_click',
      libraryId,
      place,
      screen: context.screen,
      ...(context.bookId !== undefined ? { bookId: context.bookId } : {}),
    })
  }
  return {
    onClick: () => report(),
    onAuxClick: (event: MouseEvent) => {
      if (event.button === 1) report()
    },
  }
}

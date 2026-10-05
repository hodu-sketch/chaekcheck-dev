/** 20 libraries already (409 `library_limit_reached` from the server, or the browser's own limit). */
export class LibraryLimitError extends Error {}

/** The browser would not store the change (blocked or full storage); nothing changed (cycle 07, ADR-0057). */
export class BrowserStorageError extends Error {}

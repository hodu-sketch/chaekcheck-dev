export interface RawSseEvent {
  event: string
  data: string
}

/**
 * Incremental parser for a `text/event-stream` body (WHATWG HTML "event stream interpretation").
 * Chunks may end in the middle of a line or of an event; the rest is kept until the next chunk.
 * Supports `event:` and multi-line `data:`; ignores comments, `id:` and `retry:` (no reconnect).
 */
export class SseParser {
  private buffer = ''
  private eventName = ''
  private dataLines: string[] = []

  push(chunk: string): RawSseEvent[] {
    this.buffer += chunk
    const events: RawSseEvent[] = []
    let newline = this.nextLineEnd()
    while (newline) {
      const [end, length] = newline
      const line = this.buffer.slice(0, end)
      this.buffer = this.buffer.slice(end + length)
      const event = this.processLine(line)
      if (event) events.push(event)
      newline = this.nextLineEnd()
    }
    return events
  }

  /** [index, length] of the first line break (\r\n, \n or \r), or null if the line is not complete yet. */
  private nextLineEnd(): [number, number] | null {
    for (let i = 0; i < this.buffer.length; i++) {
      const c = this.buffer[i]
      if (c === '\n') return [i, 1]
      if (c === '\r') {
        // a trailing \r may be the first half of \r\n: wait for more input
        if (i === this.buffer.length - 1) return null
        return [i, this.buffer[i + 1] === '\n' ? 2 : 1]
      }
    }
    return null
  }

  private processLine(line: string): RawSseEvent | null {
    if (line === '') {
      if (this.dataLines.length === 0) {
        this.eventName = ''
        return null
      }
      const event = { event: this.eventName || 'message', data: this.dataLines.join('\n') }
      this.eventName = ''
      this.dataLines = []
      return event
    }
    if (line.startsWith(':')) return null
    const colon = line.indexOf(':')
    const field = colon === -1 ? line : line.slice(0, colon)
    let value = colon === -1 ? '' : line.slice(colon + 1)
    if (value.startsWith(' ')) value = value.slice(1)
    if (field === 'event') this.eventName = value
    else if (field === 'data') this.dataLines.push(value)
    return null
  }
}

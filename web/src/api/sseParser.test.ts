import { describe, expect, it } from 'vitest'
import { SseParser } from './sseParser'

describe('SseParser', () => {
  it('parses Spring SseEmitter output (no space after the colon)', () => {
    const parser = new SseParser()
    const events = parser.push('event:targets\ndata:{"a":1}\n\nevent:done\ndata:{"succeeded":1}\n\n')
    expect(events).toEqual([
      { event: 'targets', data: '{"a":1}' },
      { event: 'done', data: '{"succeeded":1}' },
    ])
  })

  it('keeps a line cut in the middle until the rest arrives', () => {
    const parser = new SseParser()
    expect(parser.push('event:library-res')).toEqual([])
    expect(parser.push('ult\ndata:{"libraryId"')).toEqual([])
    expect(parser.push(':2}\n')).toEqual([])
    expect(parser.push('\n')).toEqual([{ event: 'library-result', data: '{"libraryId":2}' }])
  })

  it('handles a chunk boundary between \\r and \\n', () => {
    const parser = new SseParser()
    expect(parser.push('event: done\r')).toEqual([])
    expect(parser.push('\ndata: {}\r\n\r\n')).toEqual([{ event: 'done', data: '{}' }])
  })

  it('joins multi-line data with newlines and strips one leading space', () => {
    const parser = new SseParser()
    expect(parser.push('data: line1\ndata:  line2\n\n')).toEqual([{ event: 'message', data: 'line1\n line2' }])
  })

  it('ignores comments, ids and blank events', () => {
    const parser = new SseParser()
    expect(parser.push(': keep-alive\n\nid: 7\nretry: 100\n\nevent:x\n\n')).toEqual([])
  })

  it('splits many events arriving in one chunk, byte by byte too', () => {
    const text = 'event:a\ndata:1\n\nevent:b\ndata:2\n\n'
    const parser = new SseParser()
    const events = [...text].flatMap((c) => parser.push(c))
    expect(events.map((e) => e.event)).toEqual(['a', 'b'])
  })
})

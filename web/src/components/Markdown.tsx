import ReactMarkdown from 'react-markdown'
import styles from './Markdown.module.css'

/**
 * The elements a notice or document may use (spec R1-2): headings, paragraphs, lists, bold and links. Anything else
 * (images, code, quotes, rules) is dropped and only its text stays (`unwrapDisallowed`), so an image is never loaded.
 */
const ALLOWED = ['h1', 'h2', 'h3', 'h4', 'h5', 'h6', 'p', 'ul', 'ol', 'li', 'strong', 'a']

/**
 * The body of a notice or document (cycle 16, C2). react-markdown's defaults only: HTML in the text is not drawn
 * (no `rehype-raw`, no `dangerouslySetInnerHTML`), and the default `urlTransform` keeps http, https, mailto and the
 * like and empties any other link scheme such as `javascript:`.
 */
export function Markdown({ children, className }: { children: string; className?: string }) {
  return (
    <div className={className ? `${styles.markdown} ${className}` : styles.markdown}>
      <ReactMarkdown allowedElements={ALLOWED} unwrapDisallowed>
        {children}
      </ReactMarkdown>
    </div>
  )
}

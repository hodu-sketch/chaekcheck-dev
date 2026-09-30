import { Component, type ErrorInfo, type ReactNode } from 'react'

interface Props {
  /** Renders when a child throws; `reset` renders the children again. */
  fallback: (reset: () => void) => ReactNode
  /** When this changes while the fallback is shown, the children render again (for example a new address). */
  resetKey?: unknown
  children: ReactNode
}

interface State {
  hasError: boolean
}

/**
 * Catches render errors below it so the whole page does not go blank.
 * A class component: React 19 still has no hook for error boundaries.
 */
export class ErrorBoundary extends Component<Props, State> {
  state: State = { hasError: false }

  static getDerivedStateFromError(): State {
    return { hasError: true }
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    console.error('Screen error', error, info.componentStack)
  }

  componentDidUpdate(previous: Props) {
    if (this.state.hasError && previous.resetKey !== this.props.resetKey) this.reset()
  }

  reset = () => this.setState({ hasError: false })

  render() {
    return this.state.hasError ? this.props.fallback(this.reset) : this.props.children
  }
}

import type { WebSocketLike } from './transport'

/** 单元测试使用的可控 WebSocket。 */
export class FakeWebSocket implements WebSocketLike {
  readyState = 0
  onopen: ((event: Event) => void) | null = null
  onmessage: ((event: MessageEvent) => void) | null = null
  onerror: ((event: Event) => void) | null = null
  onclose: ((event: CloseEvent) => void) | null = null
  readonly sent: string[] = []
  closeCount = 0

  send(data: string): void {
    this.sent.push(data)
  }

  close(): void {
    this.readyState = 3
    this.closeCount++
  }

  open(): void {
    this.readyState = 1
    this.onopen?.(new Event('open'))
  }

  receive(data: unknown): void {
    this.onmessage?.({ data } as MessageEvent)
  }

  fail(): void {
    this.onerror?.(new Event('error'))
  }

  remoteClose(code = 1006): void {
    this.readyState = 3
    this.onclose?.({ code } as CloseEvent)
  }
}

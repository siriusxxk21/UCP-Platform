export type CommandId = number | string
export type CommandType = 'connect' | 'subscribe' | 'unsubscribe' | 'presence'

/** 客户端发送的协议命令。 */
export interface ProtocolCommand {
  type: CommandType
  topic?: string
  offset?: string
  state?: 'foreground' | 'background'
  data?: unknown
}

/** 客户端命令帧。 */
export interface CommandFrame {
  frame: 'command'
  id: CommandId
  command: ProtocolCommand
}

/** 命令成功结果。 */
export interface ProtocolReply {
  type: CommandType
  topic?: string
  recovered?: boolean
  headOffset?: string
  state?: 'foreground' | 'background'
  subscriptions?: Record<string, string>
}

/** 命令失败结果。 */
export interface ProtocolError {
  code: string
  message: string
  snapshotRequired?: boolean
}

/** 服务端命令回复帧。 */
export interface ReplyFrame {
  frame: 'reply'
  id: CommandId
  ok: boolean
  reply?: ProtocolReply
  error?: ProtocolError
}

/** 服务端领域事件帧。 */
export interface EventFrame {
  frame: 'event'
  eventId: string
  topic: string
  event: string
  offset?: string
  timestamp: number
  replay: boolean
  data: unknown
}

export type IncomingProtocolFrame = ReplyFrame | EventFrame
export type OutgoingProtocolFrame = CommandFrame | CommandFrame[]

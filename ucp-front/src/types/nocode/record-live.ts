/** 「某对象的记录变了」的通知：只说哪个对象的哪几条变了，不带内容；收到后按自己的权限重取。 */
export interface RecordsChanged {
  applicationId: string
  objectId: string
  /** object：说不清或不给具体记录（量大、权限范围不是全部记录、落后补发），三个列表为空 */
  kind: 'ids' | 'object'
  /** 仅当因为量大而不逐条列出时为 true */
  many: boolean
  created: string[]
  updated: string[]
  deleted: string[]
  /** 本帧合并的变更都来自同一个页签时是那个页签的标识，否则 null */
  origin: string | null
  /** 服务进程的启动标识；变了说明服务重启过，序号不再接续 */
  epoch: string
  /** 本帧覆盖该对象的通知序号 [fromSeq, seq] */
  fromSeq: number
  seq: number
}

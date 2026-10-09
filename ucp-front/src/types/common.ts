/**
 * 通用类型定义
 */

/** 分页请求参数 */
export interface PageParams {
  pageNum: number
  pageSize: number
}

/** 分页响应（后端 PageResult 格式） */
export interface PageResult<T> {
  records: T[]
  total: number
  size: number
  current: number
  pages: number
}

/** 通用 API 响应 */
export interface ApiResult<T> {
  code: number
  message: string
  data: T
}

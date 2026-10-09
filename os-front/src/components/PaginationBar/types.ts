/** 分页条组件 Props */
export interface PaginationBarProps {
  /** 当前页码（1-based，支持 v-model:current） */
  current: number
  /** 每页条数（支持 v-model:pageSize） */
  pageSize: number
  /** 数据总条数 */
  total: number
  /** 可选的每页条数列表 */
  pageSizeOptions?: string[]
  /** 分页器尺寸 */
  size?: 'default' | 'small'
}

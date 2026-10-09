/**
 * 菜单相关类型定义
 */

/** 与底座 MenuTypeEnum / CommonStatusEnum 保持一致。 */
export enum MenuType {
  DIRECTORY = 1,
  MENU = 2,
  BUTTON = 3
}

export enum MenuStatus {
  ENABLED = 0,
  DISABLED = 1
}

/**
 * 菜单（合并 api/menu.ts 和 types/index.ts 中的 Menu 定义）
 *
 * api/menu.ts 的 Menu 多了 status/menuType/createTime/updateTime 字段，
 * 以此为准。
 */
export interface Menu {
  id: string
  name: string
  permission?: string
  path: string
  component?: string
  componentName?: string
  icon?: string
  parentId: string
  sort: number
  status?: number
  menuType?: number
  visible?: boolean
  keepAlive?: boolean
  alwaysShow?: boolean
  createTime?: string
  updateTime?: string
  children?: Menu[]
}

/** 菜单查询参数 */
export interface MenuQueryParams {
  name?: string
  status?: number
}

/** 创建菜单参数 */
export interface CreateMenuParams {
  name: string
  permission?: string
  path: string
  component?: string
  componentName?: string
  icon?: string
  parentId: string
  sort: number
  status: number
  menuType: number
  visible?: boolean
  keepAlive?: boolean
  alwaysShow?: boolean
}

/** 更新菜单参数 */
export interface UpdateMenuParams {
  id: string
  name?: string
  permission?: string
  path?: string
  component?: string
  componentName?: string
  icon?: string
  parentId?: string
  sort?: number
  status?: number
  menuType?: number
  visible?: boolean
  keepAlive?: boolean
  alwaysShow?: boolean
}

import request from '@/utils/request'
import type { CreateMenuParams, Menu, MenuQueryParams, UpdateMenuParams } from '@/types/system/menu'

// 重新导出类型，保持使用方的 import 兼容
export type {
  CreateMenuParams,
  Menu,
  MenuQueryParams,
  UpdateMenuParams,
}

// 获取菜单列表（树形结构）
export function getMenuList(params?: MenuQueryParams) {
  return request.get<any[]>('/system/menu/list', { params }).then(normalizeMenuTree)
}

// 获取菜单详情
export function getMenuById(id: string) {
  return request.get<any>('/system/menu/get', { params: { id } }).then(normalizeMenu)
}

// 创建菜单
export function createMenu(params: CreateMenuParams) {
  return request.post('/system/menu/create', toMenuSavePayload(params))
}

// 更新菜单
export function updateMenu(params: UpdateMenuParams) {
  return request.put('/system/menu/update', toMenuSavePayload(params))
}

// 删除菜单
export function deleteMenu(id: string) {
  return request.delete('/system/menu/delete', { params: { id } })
}

// 获取完整菜单树（用于父菜单选择和角色权限分配）
// /list-all-simple 只返回当前租户范围内的启用菜单，并且是平铺列表；
// 菜单管理场景需要包含禁用菜单，因此使用 /list 后在前端统一组树。
export function getMenuTree() {
  return request.get<any[]>('/system/menu/list').then(normalizeMenuTree)
}

function toMenuSavePayload(params: CreateMenuParams | UpdateMenuParams) {
  const { menuType, ...rest } = params as any
  return {
    ...rest,
    type: rest.type ?? menuType,
  }
}

function normalizeMenus(list: any[] = []): Menu[] {
  return list.map(normalizeMenu)
}

function normalizeMenuTree(list: any[] = []): Menu[] {
  const menus = normalizeMenus(list)

  // The backend menu endpoints return a flat list. Preserve an already nested
  // response so this remains compatible if the server starts returning a tree.
  if (menus.some(menu => menu.children?.length)) {
    return menus
  }

  const menuMap = new Map(menus.map(menu => [menu.id, { ...menu }]))
  const roots: Menu[] = []

  for (const menu of menuMap.values()) {
    const parent = menuMap.get(menu.parentId)
    if (!parent || menu.parentId === '0') {
      roots.push(menu)
      continue
    }
    if (!parent.children) {
      parent.children = []
    }
    parent.children.push(menu)
  }

  return roots
}

function normalizeMenu(menu: any): Menu {
  return {
    ...menu,
    id: String(menu.id),
    parentId: String(menu.parentId ?? 0),
    menuType: menu.menuType ?? menu.type,
    children: menu.children ? normalizeMenus(menu.children) : menu.children,
  }
}

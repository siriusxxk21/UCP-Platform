/** 页面与独立报表共用的平台二级菜单设置，不包含资源身份或角色授权。 */
export interface PlatformNavigationSettings {
  showInMenu: boolean
  platformParentId: string
  menuName: string
  icon: string
  sort: number
}

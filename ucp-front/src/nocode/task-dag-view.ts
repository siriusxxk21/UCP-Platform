/** 只保存画布显示状态，不缓存任务资料或权限。 */
export interface TaskDagViewState {
  zoom: number
  pan: { x: number; y: number }
  collapsedIds: string[]
}

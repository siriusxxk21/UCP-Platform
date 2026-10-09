import { defineAsyncComponent, type Component } from 'vue'

/** 业务模块显式注册查看组件。流程配置不能加载任意路径或执行动态脚本。 */
const viewers = new Map<string, Component>()
export function registerBusinessForm(path: string, loader: () => Promise<{ default: Component }>) {
  if (viewers.has(path)) throw new Error('业务表单路径重复注册：' + path)
  viewers.set(path, defineAsyncComponent(loader))
}
export function businessFormViewer(path?: string): Component | undefined {
  return path ? viewers.get(path) : undefined
}

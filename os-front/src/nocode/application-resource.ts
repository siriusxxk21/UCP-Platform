import type { ApplicationResource } from '@/types/nocode/application'

/**
 * 资源以 JSON 保存，编辑和应用草稿均按相同协议生成独立副本。
 * toRaw 只解除外层代理；筛选、重排后的数组仍可能含 Vue 代理，不能直接 structuredClone。
 */
export function resourceSnapshot(resource: ApplicationResource): ApplicationResource {
  return JSON.parse(JSON.stringify(resource)) as ApplicationResource
}

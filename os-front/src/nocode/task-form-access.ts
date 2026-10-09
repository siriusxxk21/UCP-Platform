import type { InjectionKey } from 'vue'
import type { RuntimeApi } from '@/api/nocode/runtime'
import type { BusinessFileApi } from '@/api/nocode/business-file'
import type { TaskCenterApi } from '@/api/nocode/task-center'
import type { RelatedFormQuery } from '@/types/nocode/runtime'
import type { TaskWorkFormTarget } from '@/types/nocode/task-work-entries'

/** 任务表单只开放显式适配的操作，不能因公共控件增加能力而回落到应用授权。 */
export function taskFormRuntime(base: RuntimeApi, allowed: Partial<RuntimeApi>): RuntimeApi {
  return taskScopedApi(base, allowed)
}

function taskScopedApi<T extends object>(base: T, allowed: Partial<T>): T {
  const denied = async (): Promise<never> => {
    throw new Error('当前任务未授权此操作，请通过任务业务办理入口操作')
  }
  const blocked = { ...base }
  for (const key of Object.keys(base || {}) as (keyof T)[]) blocked[key] = denied as T[keyof T]
  return { ...blocked, ...allowed }
}

export interface TaskFormAccess {
  /** 仅用于隔离界面缓存；每次请求仍由服务端校验任务身份与字段权限。 */
  scope: () => string
  relatedFieldRules: (
    context: RelatedFormQuery,
    query: Parameters<RuntimeApi['evaluateFieldRules']>[0]
  ) => ReturnType<RuntimeApi['evaluateFieldRules']>
}
export const taskFormAccessKey: InjectionKey<TaskFormAccess> = Symbol.for('richuang.nocode.task-form-access')

/** 文件能力限定到任务主办理表单；不自动授予网盘浏览、目录或收藏权限。 */
export function taskFormFiles(
  base: BusinessFileApi,
  api: TaskCenterApi,
  target: () => TaskWorkFormTarget,
  binding: () => { applicationId: string; objectId: string } | undefined
): BusinessFileApi {
  const check = (query: { applicationId?: string; objectId: string }) => {
    const current = binding()
    if (
      !current ||
      query.objectId !== current.objectId ||
      (query.applicationId && query.applicationId !== current.applicationId)
    )
      throw new Error('当前任务未授权此关联对象的附件，请联系任务创建人检查配置')
  }
  return taskScopedApi(base, {
    files: query => {
      check(query)
      return api.entryFiles(target(), query)
    },
    content: query => {
      check(query)
      return api.entryFileContent(target(), query)
    },
    temporaryContent: query => {
      check(query)
      return api.entryFileTemporaryContent(target(), query)
    },
    upload: (query, file, options) => {
      check(query)
      return api.entryFileUpload(target(), query, file, options)
    },
    renew: sessionKey => api.entryFileRenew(target(), sessionKey)
  })
}

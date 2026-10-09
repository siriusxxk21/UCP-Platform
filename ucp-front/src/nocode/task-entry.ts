import type { RuntimeApi } from '@/api/nocode/runtime'
import type { TaskEntryApi } from '@/api/nocode/task-entry'
import type { TaskEntryContext, TaskDraftRef } from '@/types/nocode/task-entry'

/** 默认拒绝所有原运行方法，只有显式适配的方法才能从任务办理组件调用。 */
export function taskEntryRuntime(
  base: RuntimeApi,
  api: TaskEntryApi,
  context: TaskEntryContext,
  draft: () => TaskDraftRef | null = () => null
): RuntimeApi {
  const denied = async (): Promise<never> => {
    throw new Error('当前任务入口暂未开放此操作')
  }
  const entry = {
    applicationId: context.entry.applicationId,
    entryId: context.entry.entryId,
    version: context.entry.version
  }
  const target = (app: string, object: string) => {
    if (app !== entry.applicationId || object !== context.config.objectId)
      throw new Error('当前任务入口不能切换应用或对象')
  }
  const blocked = { ...base }
  for (const key of Object.keys(base) as (keyof RuntimeApi)[]) blocked[key] = denied
  return {
    ...blocked,
    relatedSelection: async (context, query) => {
      target(context.applicationId, context.objectId)
      return api.relatedSelection(entry, context, query)
    },
    relatedFill: async (context, query) => {
      target(context.applicationId, context.objectId)
      return api.relatedFill(entry, context, query)
    },
    relatedForm: async query => {
      target(query.applicationId, query.objectId)
      return api.relatedForm(entry, query)
    },
    viewModel: async (app, object, viewId) => {
      target(app, object)
      return api.viewModel(entry, object, viewId)
    },
    viewChildren: async query => {
      target(query.applicationId, query.objectId)
      return api.viewChildren(entry, query)
    },
    formFill: async query => {
      target(query.applicationId, query.objectId)
      return api.formFill(entry, query)
    },
    evaluateFieldRules: async query => {
      target(query.applicationId, query.objectId)
      return api.fieldRules(entry, query)
    },
    model: async (app, object) => {
      target(app, object)
      return context.model
    },
    page: async query => {
      target(query.applicationId, query.objectId)
      return api.page(entry, query)
    },
    get: async (app, object, id) => {
      target(app, object)
      return api.get(entry, id)
    },
    save: async record => {
      target(record.applicationId, record.objectId)
      return api.save(entry, record, draft())
    },
    submit: async record => {
      target(record.applicationId, record.objectId)
      return api.submit(entry, record, draft())
    },
    submitReceipt: async (app, object, key) => {
      target(app, object)
      return api.submitReceipt(entry, key)
    },
    delete: async record => {
      target(record.applicationId, record.objectId)
      return api.delete(entry, record.id, record.expectedRevision)
    },
    selection: async query => {
      target(query.applicationId, query.objectId)
      return api.selection(entry, query)
    },
    receipt: async (app, object, key) => {
      target(app, object)
      return api.receipt(entry, key)
    }
  }
}

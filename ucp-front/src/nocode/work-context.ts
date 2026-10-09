import type { InjectionKey, Ref } from 'vue'
import type { RuntimeApplication } from '@/types/nocode/runtime'

/** 使用页面实际展示的发布引用，创建草稿时不能另取新版来解释旧输入。 */
export const workEntryKey: InjectionKey<{
  application: Ref<RuntimeApplication | undefined>
  openDraft: (id: string) => void
}> = Symbol.for('richuang.nocode.work-entry')

/** 当前前台应用向账号菜单提供草稿入口；随运行页退出清理，避免打开后台应用材料。 */
export interface ApplicationWorkShelf {
  owner: symbol
  applicationId: string
  open: () => void
}

export const applicationWorkShelfKey: InjectionKey<Ref<ApplicationWorkShelf | undefined>> =
  Symbol('application-work-shelf')

import type { InjectionKey } from 'vue'
import type { SaveRecord } from '@/types/nocode/runtime'
import type { WorkDraft } from '@/types/nocode/work'

/** 办理来源仅用于界面隔离；后端仍从入口发布配置和当前身份重建授权。 */
export const taskEntrySessionKey: InjectionKey<{
  key: string
  saveDraft: (record: SaveRecord) => Promise<WorkDraft>
  loadDraft: () => Promise<WorkDraft | null>
  checkDraft: () => Promise<WorkDraft | null>
}> = Symbol.for('richuang.nocode.task-entry-session')

import { onScopeDispose, shallowReactive } from 'vue'
import type { ApplicationApi } from '@/api/nocode/application'
import type { ObjectReference } from '@/types/nocode/application'
import { errorMessage } from './data-center'

type VersionState =
  | { status: 'loading' }
  | { status: 'ready'; versionNo: number; checksum: string }
  | { status: 'error'; message: string }

/** 最新发布版本只用于对照；不修改草稿的固定引用，失败也不能显示为“已是最新”。 */
export function useLatestObjectVersions(api: Pick<ApplicationApi, 'objectVersion'>) {
  const versions = shallowReactive<Record<string, VersionState>>({})
  function reset() {
    Object.keys(versions).forEach(id => delete versions[id])
  }
  onScopeDispose(reset)

  function accept(reference: ObjectReference) {
    versions[reference.objectId] = {
      status: 'ready',
      versionNo: reference.versionNo,
      checksum: reference.checksum
    }
  }

  async function refresh(references: readonly ObjectReference[]) {
    await Promise.all(
      references.map(async reference => {
        const pending: VersionState = { status: 'loading' }
        versions[reference.objectId] = pending
        try {
          const latest = await api.objectVersion(reference.objectId)
          // 应用切换、重新查询或同步已替换当前状态时，旧响应不能回填。
          if (versions[reference.objectId] === pending) accept(latest)
        } catch (error) {
          if (versions[reference.objectId] === pending)
            versions[reference.objectId] = { status: 'error', message: errorMessage(error) }
        }
      })
    )
  }

  return { versions, refresh, accept, reset }
}

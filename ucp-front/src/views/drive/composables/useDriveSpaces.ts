import { computed, ref } from 'vue'
import { message } from 'ant-design-vue'
import { useRouter } from 'vue-router'
import { getDriveEntryPath } from '@/api/drive/entry'
import { getBusinessSpaceList, getMySpaceList } from '@/api/drive/space'
import type { DriveId, DriveSpace } from '@/types/drive'

/**
 * 网盘列表页共用的空间上下文
 *
 * 标记、分享、回收站等列表都只返回空间编号，页面需要名称与类型才能展示和定位；
 * 空间清单很小且随权限变化，每次进入页面重新取，不做跨页缓存。
 */
export function useDriveSpaces() {
  const router = useRouter()
  const spaces = ref<DriveSpace[]>([])
  const loading = ref(false)
  const spaceMap = computed(() => new Map(spaces.value.map(space => [String(space.id), space])))
  const teamSpaces = computed(() => spaces.value.filter(space => space.type === 'TEAM'))

  async function loadSpaces() {
    loading.value = true
    try {
      // 业务空间文件夹区的节点同样会出现在收藏、最近等列表里；取不到业务空间不影响其余空间
      const [mine, business] = await Promise.all([getMySpaceList(), getBusinessSpaceList().catch(() => [])])
      spaces.value = [...(mine || []), ...(business || [])]
    } catch {
      spaces.value = []
    } finally {
      loading.value = false
    }
  }

  function spaceName(spaceId: DriveId) {
    return spaceMap.value.get(String(spaceId))?.name || '-'
  }

  function spaceType(spaceId: DriveId) {
    return spaceMap.value.get(String(spaceId))?.type
  }

  /**
   * 在工作区里定位到指定节点
   *
   * 列表页的“打开”传目录自身、“所在目录”传父节点，两者只差一个编号；
   * 深链只表达位置，具体打开哪个文件仍由工作区里用户的操作决定。
   */
  async function openLocation(spaceId: DriveId, nodeId: DriveId) {
    const space = spaceMap.value.get(String(spaceId))
    if (!space) {
      message.warning('所在空间当前不可访问')
      return
    }
    // 业务空间的文件夹区在「业务文件」页里，那里不认地址栏上的位置
    if (space.type === 'BIZ') {
      void router.push('/drive/business')
      return
    }
    let path = '/'
    if (String(nodeId) !== '0') {
      path = await getDriveEntryPath(nodeId)
    }
    void router.push({
      path: space.type === 'TEAM' ? '/drive/team' : '/drive/my-file',
      query: { space: String(space.id), path }
    })
  }

  return { spaces, teamSpaces, loading, loadSpaces, spaceMap, spaceName, spaceType, openLocation }
}

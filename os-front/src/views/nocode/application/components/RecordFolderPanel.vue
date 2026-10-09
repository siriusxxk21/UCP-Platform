<script setup lang="ts">
/**
 * 表单下方的「文件夹」
 *
 * 文件夹就是网盘里的真实文件夹：这里嵌的是网盘自己的文件浏览器，根目录锁在这条记录的文件夹上、只能往下走，
 * 取数换成按记录凭据走的那组接口。对象没有配置文件夹时什么都不显示，也不加载浏览器组件。
 * 能改这条记录的人可以往里放东西，但只能改、删经由这条记录放进去的；其余的带锁标记，只能查看和下载。
 */
import { computed, defineAsyncComponent, onBeforeUnmount, ref, watch, watchEffect } from 'vue'
import { message } from 'ant-design-vue'
import { ReloadOutlined } from '@ant-design/icons-vue'
import { errorMessage } from '@/nocode/data-center'
import { usePageActivity } from '@/nocode/page-activity'
import { useNocodePlatform } from '@/nocode/platform'
import {
  createRecordFolderGateway,
  recordFolderFinderId,
  recordFolderDialogRaiser,
  recordFolderNoSourceCache,
  recordFolderTabView
} from '@/nocode/record-folder'
import { createRequestSession } from '@/nocode/request-session'
import { useUserStore } from '@/stores/user'
import type { RecordFolderCredential, RecordFolderEntry, RecordFolderTab } from '@/types/nocode/record-folder'
import { formatDateTime } from '@/utils/format'

// 浏览器组件体积大：只有真的要显示文件夹时才加载，没配文件夹的对象不为它付任何代价
const DriveFolderBrowser = defineAsyncComponent({
  loader: () => import('@/views/drive/components/DriveFolderBrowser.vue'),
  onError(_error, _retry, fail) {
    message.error('文件组件加载失败，请刷新后重试')
    fail()
  }
})

const props = defineProps<{ applicationId: string; objectId: string; recordId: string; revision?: string | null }>()

const api = useNocodePlatform().recordFolders
const activity = usePageActivity()
const userStore = useUserStore()
const cache = recordFolderNoSourceCache()
const session = createRequestSession()
const auth = { apiBase: import.meta.env.VITE_API_BASE_URL || '/api', token: () => userStore.token || '' }

const tabs = ref<RecordFolderTab[]>([])
const activeId = ref('')
/** 记录保存后文件夹可能换了一个（比如改了关联）：浏览器从根目录重新开始 */
const epoch = ref(0)
const browser = ref<{ reload: () => void }>()

const active = computed(() => tabs.value.find(tab => tab.sourceId === activeId.value) ?? tabs.value[0])
const view = computed(() => (active.value ? recordFolderTabView(active.value) : undefined))
const credential = computed<RecordFolderCredential | undefined>(() =>
  active.value
    ? {
        applicationId: props.applicationId || undefined,
        objectId: props.objectId,
        recordId: props.recordId,
        sourceId: active.value.sourceId
      }
    : undefined
)
const gateway = computed(() =>
  credential.value
    ? createRecordFolderGateway(api, credential.value, auth, { canWrite: view.value?.canWrite ?? false })
    : undefined
)
const dimmed = (tab: RecordFolderTab) => tab.state === 'RELATION_EMPTY' || tab.state === 'UNAVAILABLE'

async function open(force = false) {
  const { applicationId, objectId, recordId } = props
  const current = session.begin()
  if (!force && cache.has(objectId, applicationId)) {
    tabs.value = []
    return
  }
  try {
    const opened = await api.open(applicationId ? { applicationId, objectId, recordId } : { objectId, recordId })
    if (!current()) return
    tabs.value = opened?.tabs ?? []
    if (!tabs.value.length) cache.mark(objectId, applicationId)
  } catch {
    // 文件夹是表单的附带内容：取不到就当没有，不打断表单本身，也不弹错误
    if (current()) tabs.value = []
  }
}

watch(
  () => [props.applicationId, props.objectId, props.recordId, props.revision],
  (next, previous) => {
    // 同一条记录保存之后（修订号变了）根目录可能已经不是原来那个
    if (previous && next[2] === previous[2]) epoch.value += 1
    void open()
  },
  { immediate: true }
)
// 页签被切到后台再回来：别人可能放了新文件，重新取一次当前目录
watch(
  () => activity.resumed.value,
  () => browser.value?.reload()
)
// 组件的对话框嵌在抽屉里会被压住：这块浏览器显示着、且页面在前台时才把它们抬上来（见 recordFolderDialogRaiser）
const raiser = recordFolderDialogRaiser()
watchEffect(() => raiser.set(activity.active.value && view.value?.kind === 'browser'))
onBeforeUnmount(() => {
  session.invalidate()
  raiser.set(false)
})

async function refresh() {
  await open(true)
  browser.value?.reload()
}

// —— 最近删除 ——
const trashOpen = ref(false)
const trashLoading = ref(false)
const trashed = ref<RecordFolderEntry[]>([])
const restoring = ref('')

async function loadTrash() {
  if (!credential.value) return
  trashLoading.value = true
  try {
    trashed.value = (await api.trashList(credential.value)) || []
  } catch (cause) {
    trashed.value = []
    message.error(errorMessage(cause))
  } finally {
    trashLoading.value = false
  }
}
function openTrash() {
  trashOpen.value = true
  void loadTrash()
}
async function restore(entry: RecordFolderEntry) {
  if (!credential.value || restoring.value) return
  restoring.value = String(entry.id)
  try {
    await api.restore({ ...credential.value, id: entry.id })
    message.success('已恢复')
    browser.value?.reload()
    await loadTrash()
  } catch (cause) {
    message.error(errorMessage(cause))
  } finally {
    restoring.value = ''
  }
}
</script>

<template>
  <section v-if="tabs.length && active && view" class="record-folder-panel">
    <a-divider />
    <header class="record-folder-panel__head">
      <h3 class="record-folder-panel__title">文件夹</h3>
      <a-space>
        <a-button v-if="view.kind === 'browser' && view.canWrite" size="small" @click="openTrash">最近删除</a-button>
        <a-button size="small" @click="refresh">
          <ReloadOutlined />
          刷新
        </a-button>
      </a-space>
    </header>
    <p v-if="view.kind === 'browser' && view.canWrite" class="record-folder-panel__note">
      带锁标记的不是经由这条记录放进去的，只能查看和下载。
    </p>
    <a-tabs
      v-if="tabs.length > 1"
      :active-key="active.sourceId"
      size="small"
      class="record-folder-panel__tabs"
      @change="(key: string | number) => (activeId = String(key))"
    >
      <a-tab-pane v-for="tab in tabs" :key="tab.sourceId">
        <template #tab>
          <span :class="{ 'record-folder-panel__tab--dimmed': dimmed(tab) }">{{ tab.label }}</span>
        </template>
      </a-tab-pane>
    </a-tabs>

    <!-- 一次只渲染当前页签的浏览器；切页签时上一个随 key 销毁 -->
    <div v-if="view.kind === 'browser' && gateway && credential" class="record-folder-panel__browser">
      <DriveFolderBrowser
        :key="`${active.sourceId}:${recordId}:${epoch}`"
        ref="browser"
        :finder-id="recordFolderFinderId(credential)"
        :gateway="gateway"
        :storage-name="active.label"
        :root-role="view.rootRole"
        :allow-permission="false"
        :allow-favorite="false"
        :allow-search="true"
        :allow-write="view.canWrite"
        :locked-note="view.lockedNote"
        :locked-reason="view.lockedReason"
      />
    </div>
    <a-empty v-else class="record-folder-panel__empty" :description="view.text" />

    <a-drawer :open="trashOpen" title="最近删除" width="min(520px, 100vw)" @close="trashOpen = false">
      <p class="record-folder-panel__note">
        这里只列出你自己删除的、经由这条记录放进去的内容；其它的请联系网盘管理员从回收站恢复。
      </p>
      <a-list :data-source="trashed" :loading="trashLoading" size="small" :locale="{ emptyText: '没有可恢复的内容' }">
        <template #renderItem="{ item }: { item: RecordFolderEntry }">
          <a-list-item>
            <a-list-item-meta :title="item.name" :description="`删除时间：${formatDateTime(item.trashedAt || 0)}`" />
            <template #actions>
              <a-button type="link" size="small" :loading="restoring === String(item.id)" @click="restore(item)">
                恢复
              </a-button>
            </template>
          </a-list-item>
        </template>
      </a-list>
    </a-drawer>
  </section>
</template>

<style scoped>
.record-folder-panel {
  /* 抽屉很窄时浏览器不撑破表单，横向滚动 */
  min-width: 0;
  overflow-x: auto;
}
.record-folder-panel__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 8px;
}
.record-folder-panel__title {
  margin: 0;
  font-size: 15px;
  font-weight: 600;
}
.record-folder-panel__note {
  margin: 0 0 8px;
  color: var(--text-secondary, #6b7280);
  font-size: 12px;
}
.record-folder-panel__tabs :deep(.ant-tabs-nav) {
  margin-bottom: 8px;
}
.record-folder-panel__tab--dimmed {
  color: var(--text-secondary, #9ca3af);
}
.record-folder-panel__browser {
  display: flex;
  flex-direction: column;
  min-width: 560px;
  /* 随可用空间伸展：滚到这一块时大致占满抽屉的可视高度，最矮 420px */
  height: max(420px, calc(100vh - 240px));
  overflow: hidden;
  border: 1px solid var(--border, #e5e7eb);
  border-radius: var(--radius, 6px);
  background: var(--ant-color-bg-container, #fff);
}
.record-folder-panel__empty {
  padding: 24px 0;
}
</style>

<style>
/* 只在表单下方的文件夹显示着时生效（body 上的类由 recordFolderDialogRaiser 挂上 / 摘掉） */
body.record-folder-finder-raised .vuefinder__themer.vuefinder__modal-layout {
  z-index: 1100;
}
</style>

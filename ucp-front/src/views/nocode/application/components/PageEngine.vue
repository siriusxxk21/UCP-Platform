<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useNocodePlatform } from '@/nocode/platform'
import { errorMessage } from '@/nocode/data-center'
import { ENGINE_SANDBOX, engineFrameSrc, engineRequest } from '@/nocode/engine-block'

/**
 * 设计引擎区块运行态（laneEG，DESIGN.md §1.3–1.4）。
 *
 * 页面只传发布节点身份与当前记录；对象、权限与可写性由服务端签发令牌时判定。令牌不进 URL，只在 iframe 自己发来
 * ready / token-request 后经 postMessage 交给该 iframe 窗口；续期时重新签发（重新判一次权限）。
 */
const props = defineProps<{
  applicationId: string
  pageId?: string
  nodeId: string
  recordId?: string
  title?: string
}>()
const platform = useNocodePlatform()
const frame = ref<HTMLIFrameElement>()
const src = ref(''),
  error = ref(''),
  writable = ref(false),
  project = ref(''),
  loading = ref(false)
let latest: { token: string; expiresAt: number } | null = null
let generation = 0

async function issue() {
  if (!props.pageId || !props.recordId) return null
  const result = await platform.runtime.engineToken({
    applicationId: props.applicationId,
    pageId: props.pageId,
    nodeId: props.nodeId,
    recordId: props.recordId
  })
  latest = { token: result.token, expiresAt: result.expiresAt }
  writable.value = result.writable
  return result
}
async function open() {
  const current = ++generation
  error.value = ''
  src.value = ''
  latest = null
  if (!props.pageId || !props.recordId) return
  loading.value = true
  try {
    const result = await issue()
    if (!result || current !== generation) return
    project.value = result.project
    src.value = engineFrameSrc(result.engineUrl, result.project)
  } catch (e) {
    if (current === generation) error.value = errorMessage(e)
  } finally {
    if (current === generation) loading.value = false
  }
}
async function receive(event: MessageEvent) {
  const request = engineRequest(event, frame.value?.contentWindow, project.value)
  if (!request) return
  try {
    // 第一次 ready 用刚签发的令牌；之后的 token-request 一律重新签发（权限重新判定）。
    const fresh =
      request.type === 'engine01:ready' && latest && latest.expiresAt * 1000 - Date.now() > 60_000
        ? latest
        : await issue()
    if (!fresh) return
    // 不透明源 iframe 无法指定 targetOrigin；接收方只认父窗口且来源在允许清单内。
    frame.value?.contentWindow?.postMessage({ type: 'engine01:token', token: fresh.token }, '*')
  } catch (e) {
    error.value = errorMessage(e)
  }
}
onMounted(() => {
  addEventListener('message', receive)
  void open()
})
onBeforeUnmount(() => {
  generation++
  removeEventListener('message', receive)
})
watch(
  () => [props.recordId, props.pageId, props.nodeId],
  () => void open()
)
const status = computed(() => (writable.value ? '可编辑' : '只读：没有该记录的编辑权限，修改不会保存'))
</script>
<template>
  <a-card :title="title || '设计引擎'" class="page-engine" size="small">
    <template #extra>
      <a-tag v-if="src" :color="writable ? 'green' : 'orange'">{{ status }}</a-tag>
    </template>
    <a-empty v-if="!recordId" description="请先从列表选择一条记录" />
    <a-alert v-else-if="error" type="error" show-icon :message="`设计引擎无法打开：${error}`" />
    <a-spin v-else-if="loading" tip="正在向系统申请设计授权…" />
    <iframe
      v-else-if="src"
      ref="frame"
      class="page-engine__frame"
      title="设计引擎"
      :src="src"
      :sandbox="ENGINE_SANDBOX"
      referrerpolicy="no-referrer"
      allow=""
    />
  </a-card>
</template>
<style scoped>
.page-engine {
  margin-bottom: 16px;
}
.page-engine__frame {
  display: block;
  width: 100%;
  height: 78vh;
  min-height: 560px;
  border: 0;
}
</style>

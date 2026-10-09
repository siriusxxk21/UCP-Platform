<script setup lang="ts">
import { ref, watch, onBeforeUnmount } from 'vue'
import { useNocodePlatform } from '@/nocode/platform'
import { errorMessage } from '@/nocode/data-center'
import { formatDateTime } from '@/utils/format'
import { WorkDraftState, type WorkCursor, type WorkListItem } from '@/types/nocode/work'
const props = defineProps<{ applicationId: string; revision: number }>()
const emit = defineEmits<{ openDraft: [id: string] }>()
const api = useNocodePlatform().work
const state = ref<WorkDraftState>(WorkDraftState.DRAFT),
  items = ref<WorkListItem[]>([]),
  before = ref<WorkCursor | null>(null),
  loading = ref(false),
  error = ref('')
let generation = 0
async function load(append = false) {
  const stamp = ++generation
  loading.value = true
  error.value = ''
  if (!append) {
    items.value = []
    before.value = null
  }
  try {
    const page = await api.page({
      applicationId: props.applicationId,
      state: state.value,
      before: before.value,
      limit: 20
    })
    if (stamp !== generation) return
    const all = append ? [...items.value, ...page.items] : page.items
    items.value = [...new Map(all.map(item => [item.id, item])).values()]
    before.value = page.before
  } catch (e) {
    if (stamp === generation) error.value = errorMessage(e)
  } finally {
    if (stamp === generation) loading.value = false
  }
}
watch(
  () => [props.applicationId, props.revision, state.value],
  () => load(),
  { immediate: true }
)
onBeforeUnmount(() => generation++)
</script>
<template>
  <p class="work-help">这里保存你在当前应用中暂存的表单，以及通过草稿正式提交的材料。</p>
  <a-tabs v-model:active-key="state">
    <a-tab-pane :key="WorkDraftState.DRAFT" tab="我的草稿" />
    <a-tab-pane :key="WorkDraftState.SUBMITTED" tab="提交记录" />
    <template #rightExtra><a-button :loading="loading" @click="load()">刷新</a-button></template>
  </a-tabs>
  <a-alert v-if="error" type="error" :message="error" show-icon class="work-error">
    <template #action><a-button size="small" @click="load(!!items.length)">重试</a-button></template>
  </a-alert>
  <a-list
    :loading="loading"
    :data-source="items"
    :locale="{
      emptyText:
        state === WorkDraftState.DRAFT
          ? '暂无草稿。在业务表单中点击“暂存草稿”即可保存未完成的填写。'
          : '暂无通过草稿提交的材料'
    }"
  >
    <template #renderItem="{ item }">
      <a-list-item>
        <a-list-item-meta>
          <template #title>
            <a-button type="link" class="work-title" @click="emit('openDraft', item.id)">{{ item.formName }}</a-button>
          </template>
          <template #description>
            <div>
              {{ item.objectName }} · {{ item.recordId ? '修改记录' : '新增记录' }} · 应用 V{{
                item.applicationVersion
              }}
            </div>
            <div>更新于 {{ formatDateTime(item.updatedAt) }}</div>
          </template>
        </a-list-item-meta>
        <template #actions>
          <a-button @click="emit('openDraft', item.id)">
            {{ state === WorkDraftState.DRAFT ? '继续填写' : '查看材料' }}
          </a-button>
        </template>
      </a-list-item>
    </template>
    <template #loadMore>
      <div v-if="before" class="work-more"><a-button :loading="loading" @click="load(true)">加载更多</a-button></div>
    </template>
  </a-list>
</template>
<style scoped>
.work-help {
  color: var(--text-secondary, #64748b);
  line-height: 1.7;
}
.work-error {
  margin-bottom: 16px;
}
.work-title {
  padding: 0;
  height: auto;
  font-weight: 600;
  white-space: normal;
  text-align: left;
}
.work-more {
  margin-top: 16px;
  text-align: center;
}
</style>

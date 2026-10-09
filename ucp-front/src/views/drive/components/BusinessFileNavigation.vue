<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { AppstoreOutlined, DatabaseOutlined, ReloadOutlined, SearchOutlined } from '@ant-design/icons-vue'
import {
  businessNavigationKey,
  businessNavigationTree,
  type BusinessNavigationEntry,
  type BusinessNavigationSelection
} from '@/nocode/business-file-navigation'

const props = defineProps<{
  entries: BusinessNavigationEntry[]
  selectedEntryKey?: string
  selectedObjectId?: string
  loading?: boolean
}>()
const emit = defineEmits<{
  select: [selection: BusinessNavigationSelection]
  retry: [entryKey: string]
  refresh: []
}>()
const search = ref('')
const expandedKeys = ref<string[]>([])
const tree = computed(() => businessNavigationTree(props.entries, search.value))
const visibleExpandedKeys = computed(() =>
  search.value.trim() ? tree.value.map(node => node.key) : expandedKeys.value
)
const selectedKeys = computed(() =>
  props.selectedEntryKey && props.selectedObjectId
    ? [businessNavigationKey(props.selectedEntryKey, props.selectedObjectId)]
    : []
)
watch(
  () => props.selectedEntryKey,
  key => {
    if (key) expandedKeys.value = [...new Set([...expandedKeys.value, businessNavigationKey(key)])]
  },
  { immediate: true }
)
function toggleEntry(entryKey: string) {
  if (search.value.trim()) return
  const key = businessNavigationKey(entryKey)
  expandedKeys.value = expandedKeys.value.includes(key)
    ? expandedKeys.value.filter(item => item !== key)
    : [...expandedKeys.value, key]
}
function select(keys: (string | number)[], info: { node: { key: string | number } }) {
  const key = info.node.key ?? keys[0]
  const node = tree.value.flatMap(item => item.children ?? []).find(item => item.key === key)
  if (node?.objectId) emit('select', { entryKey: node.entryKey, objectId: node.objectId })
}
</script>

<template>
  <aside class="business-navigation" aria-label="业务文件导航">
    <div class="business-navigation__heading">
      <strong>业务文件</strong>
      <a-button
        type="text"
        size="small"
        :loading="loading"
        aria-label="刷新业务导航"
        title="刷新应用与业务对象"
        @click="emit('refresh')"
      >
        <ReloadOutlined />
      </a-button>
    </div>
    <a-input v-model:value="search" placeholder="搜索应用或业务对象" aria-label="搜索应用或业务对象" allow-clear>
      <template #prefix><SearchOutlined /></template>
    </a-input>
    <div class="business-navigation__tree">
      <a-tree
        v-if="tree.length"
        :tree-data="tree"
        :selected-keys="selectedKeys"
        :expanded-keys="visibleExpandedKeys"
        block-node
        show-icon
        @select="select"
        @expand="(keys: (string | number)[]) => (expandedKeys = keys.map(String))"
      >
        <template #icon="{ objectId }">
          <DatabaseOutlined v-if="objectId" />
          <AppstoreOutlined v-else />
        </template>
        <template #title="{ title, description, objectId, entryKey, error, loading: itemLoading }">
          <span
            class="business-navigation__node"
            :title="description ? `${title} · ${description}` : title"
            @click="!objectId && toggleEntry(entryKey)"
          >
            <span class="business-navigation__label">{{ title }}</span>
            <span v-if="objectId" class="business-navigation__description">{{ description }}</span>
            <span v-else-if="itemLoading" class="business-navigation__description">加载中…</span>
            <a-button v-if="error" type="link" size="small" @click.stop="emit('retry', entryKey)">重试</a-button>
          </span>
        </template>
      </a-tree>
      <a-empty
        v-else
        :description="loading ? '正在加载业务导航…' : search ? '没有匹配的应用或业务对象' : '暂无可用业务入口'"
      />
    </div>
  </aside>
</template>

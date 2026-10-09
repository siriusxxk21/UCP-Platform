<script setup lang="ts">
import { managementCategoryLabel } from '@/nocode/management-category'
import { computed, ref } from 'vue'
import { FolderOutlined } from '@ant-design/icons-vue'

const props = defineProps<{ title: string; categories: string[]; modelValue?: string; loading?: boolean }>()
const emit = defineEmits<{ 'update:modelValue': [value: string | undefined] }>()
const search = ref('')
const selectedKeys = computed(() => [props.modelValue === undefined ? 'all' : `category:${props.modelValue}`])
const treeData = computed(() => [
  {
    key: 'all',
    title: '全部分类',
    children: [
      { key: 'category:', title: '未分类' },
      ...props.categories.map(category => ({ key: `category:${category}`, title: managementCategoryLabel(category) }))
    ].filter(node => node.title.toLocaleLowerCase().includes(search.value.trim().toLocaleLowerCase()))
  }
])
function select(keys: (string | number)[]) {
  if (!keys.length) return
  const key = String(keys[0])
  emit('update:modelValue', key === 'all' ? undefined : key.slice('category:'.length))
}
</script>

<template>
  <aside class="nocode-category-panel" :aria-label="title">
    <div class="category-heading">
      <FolderOutlined />
      <strong>{{ title }}</strong>
    </div>
    <a-input v-model:value="search" :aria-label="`搜索${title}`" placeholder="搜索分类" allow-clear />
    <a-spin :spinning="loading" wrapper-class-name="category-tree">
      <a-tree :tree-data="treeData" :selected-keys="selectedKeys" :expanded-keys="['all']" block-node @select="select">
        <template #title="{ title: nodeTitle }">
          <span :title="nodeTitle">{{ nodeTitle }}</span>
        </template>
      </a-tree>
    </a-spin>
  </aside>
</template>

<style scoped>
.nocode-category-panel {
  display: flex;
  flex: 0 0 220px;
  flex-direction: column;
  min-height: 0;
  width: 220px;
  padding: 16px 12px;
  border-radius: 8px;
  background: var(--ant-color-bg-container, #fff);
  gap: 12px;
}
.category-heading {
  display: flex;
  align-items: center;
  gap: 8px;
}
.category-tree {
  flex: 1;
  min-height: 0;
  overflow: auto;
}
.category-tree :deep(.ant-tree-node-content-wrapper) {
  min-width: 0;
}
.category-tree :deep(.ant-tree-title) {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
@media (max-width: 900px) {
  .nocode-category-panel {
    flex-basis: 170px;
    width: 170px;
  }
}
</style>

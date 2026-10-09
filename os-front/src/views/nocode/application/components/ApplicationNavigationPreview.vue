<script setup lang="ts">
import { computed } from 'vue'
import { FileTextOutlined, FolderOutlined } from '@ant-design/icons-vue'
import AppIcon from '@/components/AppIcon.vue'
import type { ApplicationResource } from '@/types/nocode/application'
import type { Menu } from '@/types/system/menu'
import { pageNavigation, supportsPageNavigation } from '@/nocode/application-navigation'

const props = defineProps<{ resources: ApplicationResource[]; directories: Menu[]; loading: boolean }>()
const emit = defineEmits<{ settings: [resource: ApplicationResource] }>()
const pages = computed(() =>
  props.resources.filter(supportsPageNavigation).map(resource => ({
    resource,
    entry: pageNavigation(props.resources, resource.id)
  }))
)
const visible = computed(() =>
  pages.value
    .filter(item => item.entry?.config.navigationVersion === 2 && item.entry.config.showInMenu === true)
    .sort((left, right) => Number(left.entry?.config.sort || 0) - Number(right.entry?.config.sort || 0))
)
const groups = computed(() =>
  Array.from(new Set(visible.value.map(item => String(item.entry?.config.platformParentId || '')))).map(id => ({
    id,
    name: props.directories.find(item => item.id === id)?.name || (props.loading ? '目录加载中' : '目录待核验'),
    pages: visible.value.filter(item => item.entry?.config.platformParentId === id)
  }))
)
const legacy = computed(() => pages.value.filter(item => item.entry && item.entry.config.navigationVersion !== 2))
const hidden = computed(() =>
  pages.value.filter(
    item => !item.entry || (item.entry.config.navigationVersion === 2 && item.entry.config.showInMenu !== true)
  )
)
</script>

<template>
  <aside class="navigation-preview" aria-label="导航预览">
    <h3>导航预览</h3>
    <p class="hint">保存并发布后的菜单结构</p>
    <div v-for="group in groups" :key="group.id" class="navigation-group">
      <strong>
        <FolderOutlined />
        {{ group.name }}
      </strong>
      <ul>
        <li v-for="item in group.pages" :key="item.resource.id">
          <button type="button" @click="emit('settings', item.resource)">
            <AppIcon :name="String(item.entry?.config.icon || '')"><FileTextOutlined /></AppIcon>
            <span>{{ item.entry?.config.menuName || item.entry?.name || item.resource.name }}</span>
            <a-tag v-if="item.entry?.config.defaultHome" color="purple">首页</a-tag>
          </button>
        </li>
      </ul>
    </div>
    <div v-if="legacy.length" class="navigation-group legacy-navigation">
      <strong>待设置平台目录</strong>
      <p class="hint">原应用入口继续保留，可逐页设置菜单位置。</p>
      <ul>
        <li v-for="item in legacy" :key="item.resource.id">
          <button type="button" @click="emit('settings', item.resource)">
            <FileTextOutlined />
            <span>{{ item.resource.name }}</span>
          </button>
        </li>
      </ul>
    </div>
    <div v-if="hidden.length" class="navigation-group hidden-navigation">
      <strong>未显示在菜单</strong>
      <ul>
        <li v-for="item in hidden" :key="item.resource.id">
          <button type="button" @click="emit('settings', item.resource)">
            <FileTextOutlined />
            <span>{{ item.resource.name }}</span>
            <a-tag v-if="item.entry?.config.defaultHome" color="purple">首页</a-tag>
          </button>
        </li>
      </ul>
    </div>
    <a-empty v-if="!pages.length" :image="null" description="创建页面或数据视图后，设置菜单入口" />
    <p class="hint preview-footnote">共 {{ pages.length }} 个页面与视图 · {{ visible.length }} 个平台菜单入口</p>
  </aside>
</template>

<style scoped>
.navigation-preview {
  min-width: 0;
  padding: var(--spacing-lg, 16px);
  border: 1px solid var(--border, #e5e7eb);
  border-radius: var(--radius, 6px);
  background: var(--bg-container, #fff);
}
h3 {
  margin: 0 0 8px;
  font-size: 15px;
}
.hint {
  margin: 0;
  color: var(--text-secondary, #64748b);
  font-size: 12px;
  line-height: 1.7;
}
.navigation-group {
  margin-top: 20px;
}
.navigation-group > strong {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
}
.navigation-group > strong > .anticon {
  color: var(--brand, #4338ca);
}
.navigation-group ul {
  margin: 12px 0 0 6px;
  padding: 0 0 0 8px;
  border-inline-start: 1px solid var(--border, #e5e7eb);
  list-style: none;
}
.navigation-group button {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  padding: 9px 6px;
  border: 0;
  border-radius: var(--radius-sm, 4px);
  color: var(--text-primary, #1e293b);
  font: inherit;
  text-align: start;
  background: transparent;
  cursor: pointer;
}
.navigation-group button:hover,
.navigation-group button:focus-visible {
  background: var(--brand-light, #f0edff);
  color: var(--brand, #4338ca);
}
.navigation-group button > span:not(.anticon):not(.ant-tag) {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
}
.navigation-group .ant-tag {
  margin: 0;
}
.hidden-navigation,
.legacy-navigation {
  border-top: 1px solid var(--border, #e5e7eb);
  padding-top: 16px;
}
.hidden-navigation > strong {
  color: var(--text-secondary, #64748b);
}
.preview-footnote {
  margin-top: 20px;
}
</style>

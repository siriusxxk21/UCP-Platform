<script setup lang="ts">
import { computed, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import TaskList from './TaskList.vue'
import TaskTemplates from './TaskTemplates.vue'
import { taskCenterRetiredQuery } from '@/nocode/task-launch-navigation'
import { hasPermission } from '@/utils/access'
import '../management-tables.css'
import './workspace.css'

const route = useRoute(),
  router = useRouter()
const current = computed(() => route.path.split('/').at(-1) || '')
// 旧收藏地址统一回到新版，清除旧表单/草稿定位，避免误触发新版任务草稿。
watch(
  () => route.fullPath,
  () => {
    const query = taskCenterRetiredQuery(route.query)
    if (query) void router.replace({ path: route.path, query, hash: route.hash })
  },
  { immediate: true }
)
const retiring = computed(() => taskCenterRetiredQuery(route.query) !== null)
const headings: Record<string, string> = {
  manage: '任务管理',
  templates: '任务模板'
}
const heading = computed(() => headings[current.value] || '我的任务')
</script>
<template>
  <main v-if="!retiring" class="task-workspace" :aria-label="heading">
    <TaskTemplates v-if="current === 'templates'" />
    <TaskList v-else :key="current" :scope="current === 'manage' ? 'MANAGE' : 'MINE'">
      <template #page-actions>
        <a-button
          v-if="
            current !== 'manage' &&
            hasPermission('nocode:task:query') &&
            (hasPermission('nocode:task:create') || hasPermission('nocode:task:manage-all'))
          "
          @click="router.push('/nocode-app/task-center/manage')"
        >
          任务管理
        </a-button>
      </template>
    </TaskList>
  </main>
</template>

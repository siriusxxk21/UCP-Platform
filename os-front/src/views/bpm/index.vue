<script lang="ts" setup>
const modules = [
  { title: '流程模型', path: '/bpm/model', source: 'model/index.vue' },
  { title: '流程分类', path: '/bpm/category', source: 'category/index.vue' },
  { title: '流程表单', path: '/bpm/form', source: 'form/index.vue' },
  { title: '用户组', path: '/bpm/group', source: 'group/index.vue' },
  { title: '流程表达式', path: '/bpm/processExpression', source: 'processExpression/index.vue' },
  { title: '流程监听器', path: '/bpm/processListener', source: 'processListener/index.vue' },
  { title: '流程实例', path: '/bpm/processInstance', source: 'processInstance/index.vue' },
  { title: '待办任务', path: '/bpm/task/todo', source: 'task/todo/index.vue' }
]
</script>

<template>
  <div class="bpm-home">
    <a-page-header sub-title="从 yudao-ui-admin-vben 迁移的 BPM 前端模块" title="BPM 工作流" />
    <a-row :gutter="[16, 16]">
      <a-col v-for="item in modules" :key="item.path" :lg="6" :sm="12" :xs="24">
        <a-card :title="item.title" size="small">
          <p class="source">源码：src/views/bpm/_vben-src/{{ item.source }}</p>
          <router-link :to="item.path">进入页面</router-link>
        </a-card>
      </a-col>
    </a-row>
  </div>
</template>

<style scoped>
.bpm-home {
  height: 100%;
}

.source {
  min-height: 40px;
  color: #667085;
  font-size: 12px;
}
</style>

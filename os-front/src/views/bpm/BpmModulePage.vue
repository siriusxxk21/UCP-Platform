<script lang="ts" setup>
import { computed } from 'vue'

const props = defineProps<{
  title: string
  description?: string
  sourcePath?: string
}>()

const legacyPath = computed(() => props.sourcePath || `src/views/bpm/_vben-src/${props.title}`)
</script>

<template>
  <div class="bpm-module-page">
    <a-page-header :sub-title="description || 'BPM 模块页面已接入 devops-ui 路由'" :title="title" />
    <a-alert
      :description="`原 Vben 页面保留在 ${legacyPath}，当前页面使用 devops-ui 原生占位承载，后续可按目标组件体系逐页替换。`"
      message="页面源码已迁移"
      show-icon
      type="info"
    />
  </div>
</template>

<style scoped>
.bpm-module-page {
  height: 100%;
}
</style>

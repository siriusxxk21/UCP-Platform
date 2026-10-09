<script setup lang="ts">
/** 面包屑项 */
export interface BreadcrumbItem {
  title: string
  path?: string
}

withDefaults(defineProps<{
  /** 面包屑项列表（中间项 path 可选，末项 path 为空表示当前页） */
  items: BreadcrumbItem[]
  /** 是否显示首页项，默认 true（项目空间保持为 true，系统级页面传入 false） */
  showHome?: boolean
  /** 首页文字，默认"首页" */
  homeText?: string
  /** 首页路径，默认 /dashboard */
  homePath?: string
}>(), {
  showHome: true,
  homeText: '首页',
  homePath: '/dashboard',
})
</script>

<template>
  <div class="app-breadcrumb">
    <a-breadcrumb separator=">">
      <!-- 首页（受 showHome 控制：系统级页面移除，项目空间保留） -->
      <a-breadcrumb-item v-if="showHome">
        <router-link v-if="homePath" :to="homePath">{{ homeText }}</router-link>
        <span v-else>{{ homeText }}</span>
      </a-breadcrumb-item>
      <!-- 动态项 -->
      <template v-for="(item, index) in items" :key="index">
        <a-breadcrumb-item v-if="item.title">
          <router-link v-if="item.path" :to="item.path">{{ item.title }}</router-link>
          <span v-else>{{ item.title }}</span>
        </a-breadcrumb-item>
      </template>
    </a-breadcrumb>
  </div>
</template>

<style scoped>
.app-breadcrumb {
  font-size: 13px;
  line-height: 1.5;
  white-space: nowrap;
}

/* 可导航链接：灰色文字，hover 渐变为品牌色并显示浅底 */
.app-breadcrumb :deep(.ant-breadcrumb-link a) {
  color: var(--text-secondary, #6B7280);
  font-weight: 400;
  padding: 2px 6px;
  margin: -2px -6px;
  border-radius: 4px;
  transition: color 0.2s ease, background-color 0.2s ease;
}

.app-breadcrumb :deep(.ant-breadcrumb-link a:hover) {
  color: var(--brand, #4338CA);
  background-color: var(--brand-light, #EEF2FF);
}

.app-breadcrumb :deep(.ant-breadcrumb-link a:active) {
  color: #3730A3;
}

/* 末级（当前页）：最深色 + 中粗体，强调层级终节点 */
.app-breadcrumb :deep(.ant-breadcrumb-item:last-child .ant-breadcrumb-link) {
  color: var(--text-primary, #1F2937);
  font-weight: 500;
}

/* 分隔符 ">" 颜色 */
.app-breadcrumb :deep(.ant-breadcrumb-separator) {
  color: var(--text-tertiary, #9CA3AF);
  margin: 0 6px;
  user-select: none;
}
</style>

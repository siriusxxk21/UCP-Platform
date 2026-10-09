<script setup lang="ts">
import { antdTheme } from '@/theme/antd-theme'
import zhCN from 'ant-design-vue/locale/zh_CN'
import FeedbackWidget from '@/components/system-feedback/FeedbackWidget.vue'
</script>

<template>
  <a-config-provider :theme="antdTheme" :locale="zhCN">
    <router-view />
    <FeedbackWidget />
  </a-config-provider>
</template>

<style>
* {
  margin: 0;
  padding: 0;
  box-sizing: border-box;
}

body {
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, sans-serif;
  -webkit-font-smoothing: antialiased;
  -moz-osx-font-smoothing: grayscale;
}
</style>

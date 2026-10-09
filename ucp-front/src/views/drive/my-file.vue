<script setup lang="ts">
/** 我的文件：个人空间工作区，空间由后端在首次进入时自动建立 */
import DriveWorkspace from './components/DriveWorkspace.vue'

defineOptions({ name: 'DriveMyFile' })
</script>

<template>
  <DriveWorkspace mode="PERSONAL" />
</template>

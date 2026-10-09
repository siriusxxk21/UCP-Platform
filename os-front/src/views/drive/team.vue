<script setup lang="ts">
/** 团队空间：在已获授权的团队空间之间切换，工作区与个人空间一致 */
import DriveWorkspace from './components/DriveWorkspace.vue'

defineOptions({ name: 'DriveTeam' })
</script>

<template>
  <DriveWorkspace mode="TEAM" />
</template>

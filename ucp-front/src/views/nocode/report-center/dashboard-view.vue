<script setup lang="ts">
import { useRoute, useRouter } from 'vue-router'
import { useNocodePlatform } from '@/nocode/platform'
import type { DashboardRuntimeTransport } from '@/types/nocode/dashboard-runtime'
import DashboardRuntime from './components/DashboardRuntime.vue'
const api = useNocodePlatform().reportCenter,
  route = useRoute(),
  router = useRouter()
const transport: DashboardRuntimeTransport = {
  load: async () => ({ dashboard: await api.dashboardPublished(String(route.query.id || '')) }),
  query: api.dashboardQuery,
  options: api.dashboardOptions,
  details: api.dashboardDetails,
  export: api.dashboardExport,
  preference: api.dashboardPreference,
  favorite: api.dashboardFavorite,
  visit: api.dashboardVisit
}
</script>
<template>
  <DashboardRuntime :transport="transport" :context-key="String(route.query.id || '')">
    <template #leading-actions>
      <a-button @click="router.push('/nocode/report-center/dashboards')">返回仪表板</a-button>
    </template>
    <template #trailing-actions>
      <a-button @click="router.push('/nocode/report-center/home')">报表工作台</a-button>
    </template>
  </DashboardRuntime>
</template>

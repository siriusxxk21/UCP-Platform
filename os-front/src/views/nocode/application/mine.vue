<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { AppstoreOutlined } from '@ant-design/icons-vue'
import { useNocodePlatform } from '@/nocode/platform'
import { errorMessage } from '@/nocode/data-center'
import type { ApplicationRow } from '@/types/nocode/application'
const platform = useNocodePlatform(),
  router = useRouter()
const apps = ref<ApplicationRow[]>([]),
  search = ref(''),
  loading = ref(false),
  error = ref('')
const visible = computed(() =>
  apps.value.filter(a => (a.name + ' ' + a.code).toLowerCase().includes(search.value.toLowerCase()))
)
async function load() {
  loading.value = true
  error.value = ''
  try {
    apps.value = await platform.runtime.mine()
  } catch (e) {
    error.value = errorMessage(e)
  } finally {
    loading.value = false
  }
}
onMounted(load)
</script>
<template>
  <section class="my-apps">
    <header>
      <div>
        <h2>我的应用</h2>
        <p>已发布并授权给你的业务应用</p>
      </div>
      <a-space>
        <a-input-search v-model:value="search" placeholder="搜索应用" allow-clear />
        <a-button @click="load">刷新</a-button>
        <a-button v-if="platform.hasPermission('nocode:app:query')" @click="router.push('/nocode-app/application')">
          管理应用
        </a-button>
      </a-space>
    </header>
    <a-alert v-if="error" :message="error" type="error" show-icon />
    <a-spin :spinning="loading">
      <div class="app-grid">
        <a-card v-for="app in visible" :key="app.id" hoverable>
          <template #title>
            <a-space>
              <AppstoreOutlined />
              {{ app.name }}
            </a-space>
          </template>
          <p>{{ app.description || '进入应用开始处理业务' }}</p>
          <a-button type="primary" @click="router.push({ path: '/nocode-app/runtime', query: { id: app.id } })">
            进入应用
          </a-button>
          <span class="version">V{{ app.publishedVersion }}</span>
        </a-card>
      </div>
      <a-empty v-if="!loading && !visible.length" description="暂无可访问的应用" />
    </a-spin>
  </section>
</template>
<style scoped>
.my-apps {
  padding: 24px;
}
.my-apps header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  flex-wrap: wrap;
  margin-bottom: 24px;
}
.my-apps h2 {
  margin: 0;
}
.my-apps p {
  color: #8c8c8c;
  margin-top: 6px;
}
.app-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(260px, 1fr));
  gap: 20px;
}
.version {
  float: right;
  color: #8c8c8c;
}
</style>

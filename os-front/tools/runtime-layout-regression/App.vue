<script setup lang="ts">
import { ref } from 'vue'
const narrow = ref(false)
</script>

<template>
  <a-config-provider :theme="{ token: { colorPrimary: '#4f36c8' } }">
    <div class="fixture-controls">
      <span>运行页布局夹具（内存数据）</span>
      <router-link to="/nocode-app/runtime?id=multi">多页</router-link>
      <router-link to="/nocode-app/runtime?id=single">单页</router-link>
      <router-link to="/nocode-app/runtime?id=error">加载失败</router-link>
      <a-button @click="narrow = !narrow">{{ narrow ? '恢复宽度' : '窄内容区' }}</a-button>
    </div>
    <main class="fixture-content" :class="{ 'fixture-content--narrow': narrow }">
      <router-view />
    </main>
  </a-config-provider>
</template>

<style scoped>
.fixture-controls {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 16px;
  flex-wrap: wrap;
}
.fixture-content {
  width: 100%;
  height: calc(100vh - 80px);
}
.fixture-content--narrow {
  width: 360px;
}
</style>

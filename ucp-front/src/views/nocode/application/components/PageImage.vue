<script setup lang="ts">
import { ref, watch } from 'vue'
import { pageImageUrl } from '@/nocode/page-image'
import { PageImageFit, type PageDisplay } from '@/types/nocode/application-ui'
const props = defineProps<{ display?: PageDisplay | null }>()
const url = ref(''),
  error = ref(''),
  loading = ref(false)
let version = 0
watch(
  () => props.display?.imageFileId,
  async id => {
    const current = ++version
    url.value = ''
    error.value = ''
    loading.value = !!id
    if (!id) return
    try {
      const result = await pageImageUrl(id)
      if (current === version) url.value = result
    } catch (e) {
      if (current === version) error.value = (e as Error).message
    } finally {
      if (current === version) loading.value = false
    }
  },
  { immediate: true }
)
</script>
<template>
  <a-skeleton-image v-if="loading" />
  <a-alert v-else-if="error" type="warning" :message="error" show-icon />
  <img
    v-else-if="url"
    :src="url"
    :alt="display?.imageAlt || '页面图片'"
    :style="{
      height: `${display?.imageHeight || 160}px`,
      objectFit: display?.imageFit === PageImageFit.COVER ? 'cover' : 'contain'
    }"
    class="page-image"
    @error="error = '图片暂时无法加载'"
  />
  <a-empty v-else description="尚未配置展示图片" />
</template>
<style scoped>
.page-image {
  display: block;
  width: 100%;
  max-width: 100%;
  border-radius: inherit;
}
</style>

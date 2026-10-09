<script setup lang="ts">
import IconSelector from '@/components/IconSelector.vue'
import type { Menu } from '@/types/system/menu'
import type { PlatformNavigationSettings } from '@/types/nocode/platform-navigation'

interface Props {
  directories: Menu[]
  directoriesLoading: boolean
  directoryError?: string
  disabled?: boolean
  unavailable?: boolean
}
withDefaults(defineProps<Props>(), { directoryError: '', disabled: false, unavailable: false })
const emit = defineEmits<{ reload: [] }>()
const form = defineModel<PlatformNavigationSettings>({ required: true })
</script>

<template>
  <a-form-item label="显示在平台菜单">
    <a-switch
      v-model:checked="form.showInMenu"
      aria-label="显示在平台菜单"
      :disabled="disabled || (unavailable && !form.showInMenu)"
    />
  </a-form-item>
  <template v-if="form.showInMenu">
    <a-form-item label="平台一级目录" required>
      <a-select
        v-model:value="form.platformParentId"
        aria-label="平台一级目录"
        placeholder="选择已有一级目录"
        :loading="directoriesLoading"
        :options="directories.map(item => ({ value: item.id, label: item.name }))"
      />
      <p class="hint">页面将作为该目录下的二级菜单显示。</p>
      <p v-if="!directoriesLoading && !directoryError && !directories.length" class="hint">
        尚无可用目录，请由平台管理员在系统菜单管理中创建。
      </p>
      <a-alert v-if="directoryError" type="error" :message="directoryError" show-icon>
        <template #action>
          <a-button size="small" @click="emit('reload')">重试</a-button>
        </template>
      </a-alert>
    </a-form-item>
    <a-form-item label="菜单名称" required>
      <a-input v-model:value="form.menuName" aria-label="菜单名称" :maxlength="50" />
    </a-form-item>
    <a-row :gutter="16">
      <a-col :span="16">
        <a-form-item label="图标">
          <IconSelector v-model="form.icon" />
        </a-form-item>
      </a-col>
      <a-col :span="8">
        <a-form-item label="排序" required>
          <a-input-number v-model:value="form.sort" aria-label="菜单排序" :min="0" :max="9999" :precision="0" />
        </a-form-item>
      </a-col>
    </a-row>
  </template>
</template>

<style scoped>
.hint {
  color: var(--text-secondary);
  margin: var(--spacing-sm) 0 0;
  font-size: var(--font-size-sm);
  line-height: 1.7;
}
</style>

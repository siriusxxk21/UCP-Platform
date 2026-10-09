<script lang="ts" setup>
defineOptions({ name: 'HttpRequestSetting' })

const model = defineModel<Record<string, any>>({ default: () => ({}) })
</script>

<template>
  <a-form layout="vertical">
    <a-form-item label="请求地址">
      <a-input v-model:value="model.url" placeholder="请输入请求地址" />
    </a-form-item>
    <a-form-item label="请求说明">
      <a-textarea v-model:value="model.remark" :auto-size="{ minRows: 3 }" placeholder="请输入请求说明" />
    </a-form-item>
  </a-form>
</template>

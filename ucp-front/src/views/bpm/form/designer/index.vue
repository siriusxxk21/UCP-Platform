<script lang="ts" setup>
import type { FormInstance } from 'ant-design-vue'
import type { BpmFormApi } from '@/api/bpm/form'
import FcDesigner from '@form-create/antd-designer'
import { ArrowLeftOutlined, SaveOutlined } from '@ant-design/icons-vue'
import { message } from 'ant-design-vue'
import { computed, nextTick, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { createForm, getForm, updateForm } from '@/api/bpm/form'
import { encodeConf, encodeFields, setConfAndFields, useFormCreateDesigner } from '@/components/form-create'

defineOptions({ name: 'BpmFormEditor' })

type DesignerAction = 'create' | 'edit' | 'copy'

const route = useRoute()
const router = useRouter()
const loading = ref(false)
const saving = ref(false)
const saveModalOpen = ref(false)
const mobilePanel = ref('canvas')
const formRef = ref<FormInstance>()
const designerRef = ref<InstanceType<typeof FcDesigner>>()
const flowFormConfig = ref<BpmFormApi.Form>()
const formModel = reactive<Partial<BpmFormApi.Form>>({
  name: '',
  status: 0,
  remark: ''
})

const action = computed<DesignerAction>(() => {
  const type = String(route.query.type || 'create')
  return ['create', 'edit', 'copy'].includes(type) ? (type as DesignerAction) : 'create'
})

const currentFormId = computed(() => {
  const id = action.value === 'copy' ? route.query.copyId : route.query.id
  return Array.isArray(id) ? id[0] : id
})

const pageTitle = computed(() => {
  if (action.value === 'copy') return '复制流程表单'
  if (action.value === 'edit') return '编辑流程表单'
  return '新建流程表单'
})

const designerConfig = ref({
  switchType: [],
  autoActive: true,
  useTemplate: false,
  formOptions: {
    form: {
      labelWidth: '100px'
    }
  },
  fieldReadonly: false,
  hiddenDragMenu: false,
  hiddenDragBtn: false,
  hiddenMenu: [],
  hiddenItem: [],
  hiddenItemConfig: {},
  disabledItemConfig: {},
  showSaveBtn: false,
  showConfig: true,
  showBaseForm: true,
  showControl: true,
  showPropsForm: true,
  showEventForm: true,
  showValidateForm: true,
  showFormConfig: true,
  showInputData: true,
  showDevice: true,
  appendConfigData: []
})

const formRules = {
  name: [{ required: true, message: '请输入表单名称', trigger: 'blur' }]
}

useFormCreateDesigner(designerRef)

async function loadFormConfig(id: string | number) {
  loading.value = true
  try {
    const detail = await getForm(id)
    flowFormConfig.value = detail
    Object.assign(formModel, {
      id: action.value === 'copy' ? undefined : detail.id,
      name: action.value === 'copy' ? `${detail.name}_copy` : detail.name,
      status: detail.status ?? 0,
      remark: detail.remark
    })
    await nextTick()
    setConfAndFields(designerRef, detail.conf, detail.fields)
  } catch (error: any) {
    console.error('加载流程表单配置失败:', error)
    message.error(error.message || '加载流程表单配置失败')
  } finally {
    loading.value = false
  }
}

async function initializeDesigner() {
  if (currentFormId.value) {
    await loadFormConfig(currentFormId.value)
    return
  }
  Object.assign(formModel, {
    name: '',
    status: 0,
    remark: ''
  })
}

function handleOpenSave() {
  saveModalOpen.value = true
}

async function handleSave() {
  await formRef.value?.validate()
  saving.value = true
  try {
    const payload: BpmFormApi.Form = {
      ...(flowFormConfig.value || {}),
      ...(formModel as BpmFormApi.Form),
      conf: encodeConf(designerRef),
      fields: encodeFields(designerRef),
      status: formModel.status ?? 0,
      remark: formModel.remark || ''
    }
    if (action.value === 'edit' && payload.id) {
      await updateForm(payload)
    } else {
      delete payload.id
      await createForm(payload)
    }
    message.success('保存成功')
    saveModalOpen.value = false
    handleBack()
  } catch (error: any) {
    console.error('保存流程表单失败:', error)
    message.error(error.message || '保存流程表单失败')
  } finally {
    saving.value = false
  }
}

function handleBack() {
  router.push('/bpm/form')
}

onMounted(initializeDesigner)
</script>

<template>
  <div class="bpm-form-editor" :data-mobile-panel="mobilePanel">
    <a-card :bordered="false" class="editor-card">
      <template #title>
        <div class="page-title">{{ pageTitle }}</div>
      </template>
      <template #extra>
        <a-space>
          <a-button @click="handleBack">
            <ArrowLeftOutlined />
            返回
          </a-button>
          <a-button type="primary" @click="handleOpenSave">
            <SaveOutlined />
            保存
          </a-button>
        </a-space>
      </template>

      <a-radio-group
        v-model:value="mobilePanel"
        class="mobile-designer-panels"
        aria-label="表单设计区域"
        button-style="solid"
      >
        <a-radio-button value="components">组件</a-radio-button>
        <a-radio-button value="canvas">画布</a-radio-button>
        <a-radio-button value="properties">属性</a-radio-button>
      </a-radio-group>
      <a-spin :spinning="loading">
        <FcDesigner ref="designerRef" :config="designerConfig" height="calc(100vh - 190px)" />
      </a-spin>
    </a-card>

    <a-modal v-model:open="saveModalOpen" :confirm-loading="saving" :title="pageTitle" @ok="handleSave">
      <a-form ref="formRef" :model="formModel" :rules="formRules" layout="vertical">
        <a-form-item label="表单名称" name="name">
          <a-input v-model:value="formModel.name" placeholder="请输入表单名称" />
        </a-form-item>
        <a-form-item label="状态" name="status">
          <a-radio-group v-model:value="formModel.status">
            <a-radio :value="0">启用</a-radio>
            <a-radio :value="1">禁用</a-radio>
          </a-radio-group>
        </a-form-item>
        <a-form-item label="备注" name="remark">
          <a-textarea v-model:value="formModel.remark" :auto-size="{ minRows: 3 }" placeholder="请输入备注" />
        </a-form-item>
      </a-form>
    </a-modal>
  </div>
</template>

<style scoped>
.bpm-form-editor {
  padding: 24px;
}

.editor-card {
  min-height: calc(100vh - 96px);
}

.page-title {
  font-size: 16px;
  font-weight: 600;
  color: #1f2937;
}
.mobile-designer-panels {
  display: none;
}

@media (max-width: 1023px) {
  .bpm-form-editor {
    padding: 0;
  }
  .editor-card :deep(> .ant-card-head) {
    padding: 0 12px;
  }
  .editor-card :deep(.ant-card-head-wrapper) {
    flex-wrap: wrap;
    gap: 8px;
    padding: 12px 0;
  }
  .editor-card :deep(.ant-card-head-title) {
    flex-basis: 100%;
    padding: 0;
  }
  .editor-card :deep(> .ant-card-body) {
    padding: 12px;
  }
  .mobile-designer-panels {
    display: flex;
    margin-bottom: 12px;
  }
  .mobile-designer-panels :deep(.ant-radio-button-wrapper) {
    flex: 1;
    height: 44px;
    line-height: 42px;
    text-align: center;
  }
  .bpm-form-editor :deep(._fc-designer) {
    min-height: 360px;
    height: calc(100dvh - 290px) !important;
  }
  /* 复用设计器原有三个区域及数据状态，窄屏只展示当前区域。 */
  .bpm-form-editor :deep(._fc-l-menu),
  .bpm-form-editor :deep(._fc-l),
  .bpm-form-editor :deep(._fc-m),
  .bpm-form-editor :deep(._fc-r),
  .bpm-form-editor :deep(._fc-l-close),
  .bpm-form-editor :deep(._fc-r-close) {
    display: none !important;
  }
  .bpm-form-editor[data-mobile-panel='components'] :deep(._fc-l),
  .bpm-form-editor[data-mobile-panel='canvas'] :deep(._fc-m),
  .bpm-form-editor[data-mobile-panel='properties'] :deep(._fc-r) {
    display: block !important;
    flex: 1 1 100% !important;
    width: 100% !important;
    min-width: 0 !important;
    max-width: 100% !important;
  }
  .bpm-form-editor :deep(._fc-m-tools) {
    height: auto !important;
    min-height: 44px;
    flex-wrap: wrap;
    gap: 8px;
    padding: 8px;
  }
  .bpm-form-editor :deep(._fc-m-drag) {
    width: 100% !important;
    min-width: 0;
    padding: 12px;
  }
}
</style>

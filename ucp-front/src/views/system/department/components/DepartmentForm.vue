<script setup lang="ts">
import {reactive, ref, watch} from 'vue'
import type {FormInstance} from 'ant-design-vue'
import {message} from 'ant-design-vue'
import type {Organization} from '@/api/system/organization'
import type {Department, DepartmentFormData} from '@/api/system/department'
import {createDepartment, getDepartmentTreeByOrgId, updateDepartment,} from '@/api/system/department'

const props = defineProps<{
  visible: boolean
  orgList: Organization[]
  currentOrgId?: string
  defaultParentId?: string
  editData?: Department | null
}>()

const emit = defineEmits<{
  (e: 'update:visible', visible: boolean): void
  (e: 'success'): void
}>()

const formRef = ref<FormInstance>()
const loading = ref(false)
const deptTree = ref<Department[]>([])

const title = ref('新增部门')

const formData = reactive<DepartmentFormData & { id?: string }>({
  orgId: '',
  deptCode: '',
  deptName: '',
  parentId: undefined,
  phone: '',
  email: '',
  sortOrder: 0,
  status: 0,
})

const rules = {
  orgId: [{ required: true, message: '请选择所属组织', trigger: 'change' }],
  deptName: [{ required: true, message: '请输入部门名称', trigger: 'blur' }],
}

// 监听 visible 变化，初始化表单
watch(() => props.visible, async (val) => {
  if (val) {
    if (props.editData) {
      title.value = '编辑部门'
      formData.id = props.editData.id
      formData.orgId = props.editData.orgId
      formData.deptCode = props.editData.deptCode
      formData.deptName = props.editData.deptName
      formData.parentId = props.editData.parentId && props.editData.parentId !== '0'
        ? props.editData.parentId
        : undefined
      formData.phone = props.editData.phone || ''
      formData.email = props.editData.email || ''
      formData.sortOrder = props.editData.sortOrder || 0
      formData.status = props.editData.status
      deptTree.value = await getDepartmentTreeByOrgId(props.editData.orgId)
    }
    else {
      title.value = '新增部门'
      resetForm()
      if (props.currentOrgId) {
        formData.orgId = props.currentOrgId
        formData.parentId = props.defaultParentId
        deptTree.value = await getDepartmentTreeByOrgId(props.currentOrgId)
      }
    }
  }
})

async function handleOrgChange(orgId: string) {
  formData.parentId = undefined
  deptTree.value = await getDepartmentTreeByOrgId(orgId)
}

function resetForm() {
  formData.id = undefined
  formData.orgId = props.currentOrgId || ''
  formData.deptCode = ''
  formData.deptName = ''
  formData.parentId = props.defaultParentId
  formData.phone = ''
  formData.email = ''
  formData.sortOrder = 0
  formData.status = 0
  deptTree.value = []
}

function handleOk() {
  formRef.value?.validate().then(async () => {
    loading.value = true
    try {
      if (formData.id) {
        await updateDepartment(formData.id, { ...formData })
        message.success('编辑成功')
      }
      else {
        await createDepartment({ ...formData })
        message.success('新增成功')
      }
      emit('update:visible', false)
      emit('success')
    }
    catch (e) {
      console.error('保存失败', e)
    }
    finally {
      loading.value = false
    }
  })
}

function handleCancel() {
  emit('update:visible', false)
  formRef.value?.resetFields()
}
</script>

<template>
  <a-modal :open="visible" :title="title" width="580px" :confirm-loading="loading" @ok="handleOk"
    @cancel="handleCancel">
    <a-form ref="formRef" :model="formData" :rules="rules" :label-col="{ span: 6 }" :wrapper-col="{ span: 16 }">
      <a-form-item label="所属组织" name="orgId">
        <a-select v-model:value="formData.orgId" :disabled="Boolean(formData.id)" placeholder="请选择所属组织"
          @change="handleOrgChange">
          <a-select-option v-for="org in orgList" :key="org.id" :value="org.id">
            {{ org.orgName }}
          </a-select-option>
        </a-select>
      </a-form-item>
      <a-form-item label="上级部门" name="parentId">
        <a-tree-select v-model:value="formData.parentId" :tree-data="deptTree"
          :field-names="{ label: 'deptName', value: 'id', children: 'children' }" placeholder="请选择上级部门（不选则为顶级）"
          allow-clear tree-default-expand-all style="width: 100%" />
      </a-form-item>
      <a-form-item v-if="formData.id" label="部门编码" name="deptCode">
        <a-input v-model:value="formData.deptCode" disabled />
      </a-form-item>
      <a-form-item v-else label="部门编码">
        <a-typography-text type="secondary">
          保存后按组织编码自动生成，例如 ORG-001
        </a-typography-text>
      </a-form-item>
      <a-form-item label="部门名称" name="deptName">
        <a-input v-model:value="formData.deptName" placeholder="请输入部门名称" />
      </a-form-item>
      <a-form-item label="联系电话" name="phone">
        <a-input v-model:value="formData.phone" placeholder="请输入联系电话" />
      </a-form-item>
      <a-form-item label="电子邮箱" name="email">
        <a-input v-model:value="formData.email" placeholder="请输入电子邮箱" />
      </a-form-item>
      <a-form-item label="排序" name="sortOrder">
        <a-input-number v-model:value="formData.sortOrder" :min="0" :max="9999" style="width: 100%" />
      </a-form-item>
      <a-form-item label="状态" name="status">
        <a-radio-group v-model:value="formData.status">
          <a-radio :value="0">
            启用
          </a-radio>
          <a-radio :value="1">
            禁用
          </a-radio>
        </a-radio-group>
      </a-form-item>
    </a-form>
  </a-modal>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ApartmentOutlined, ArrowRightOutlined, PlusOutlined } from '@ant-design/icons-vue'
import type { FieldInfo, TableRelation } from '@/types'

// Props
interface Props {
  /** 当前主表名 */
  currentTableName: string
  /** 当前主表字段 */
  currentTableFields: FieldInfo[]
  /** 所有已选中的表 */
  selectedTables: string[]
  /** 所有表的字段映射 */
  tableFieldsMap: Record<string, FieldInfo[]>
  /** 已有的关系配置 */
  modelValue?: TableRelation[]
}

const props = withDefaults(defineProps<Props>(), {
  modelValue: () => [],
})

// Emits
const emit = defineEmits<{
  (e: 'update:modelValue', value: TableRelation[]): void
}>()

// 内部状态
const relations = computed({
  get: () => props.modelValue || [],
  set: val => emit('update:modelValue', val),
})

const modalVisible = ref(false)
const isEdit = ref(false)
const editIndex = ref(-1)

// 表单状态
const defaultFormState: TableRelation = {
  relationType: 'oneToMany',
  slaveTableName: '',
  slaveClassName: '',
  masterField: '',
  slaveField: '',
  propertyName: '',
  cascadeDelete: false,
  lazyLoad: true,
}

const formState = ref<TableRelation>({ ...defaultFormState })

// 计算属性：当前表字段选项
const currentTableFieldOptions = computed(() => {
  return props.currentTableFields.map(field => ({
    value: field.columnName,
    label: `${field.columnName} (${field.columnComment || field.javaType})`,
  }))
})

// 计算属性：从表选项（排除当前主表）
const slaveTableOptions = computed(() => {
  return props.selectedTables
    .filter(name => name !== props.currentTableName)
    .map(name => ({
      value: name,
      label: name,
    }))
})

// 计算属性：从表字段选项
const slaveFieldOptions = computed(() => {
  if (!formState.value.slaveTableName)
    return []
  const fields = props.tableFieldsMap[formState.value.slaveTableName] || []
  return fields.map(field => ({
    value: field.columnName,
    label: `${field.columnName} (${field.columnComment || field.javaType})`,
  }))
})

// 方法：获取关系类型标签
function getRelationTypeLabel(type: string) {
  const map: Record<string, string> = {
    oneToOne: '一对一',
    oneToMany: '一对多',
  }
  return map[type] || type
}

// 方法：获取关系标题
function getRelationTitle(relation: TableRelation) {
  return `${getRelationTypeLabel(relation.relationType)}: ${relation.slaveTableName}`
}

// 方法：显示添加弹窗
function showAddRelationModal() {
  isEdit.value = false
  editIndex.value = -1
  formState.value = { ...defaultFormState }
  modalVisible.value = true
}

// 方法：编辑关系
function editRelation(relation: TableRelation, index: number) {
  isEdit.value = true
  editIndex.value = index
  formState.value = { ...relation }
  modalVisible.value = true
}

// 方法：删除关系
function removeRelation(index: number) {
  const newRelations = [...relations.value]
  newRelations.splice(index, 1)
  relations.value = newRelations
}

// 方法：从表变化处理
function onSlaveTableChange(tableName: string) {
  // 自动推断外键字段名（主表名_id）
  const defaultFkName = `${props.currentTableName.toLowerCase()}_id`
  formState.value.slaveField = defaultFkName

  // 自动推断属性名
  const slaveClassName = tableName.replace(/^(sys_|t_|tb_)/, '')
  formState.value.slaveClassName = toCamelCase(slaveClassName, true)
  formState.value.propertyName = `${toCamelCase(slaveClassName, false)}List`
}

// 方法：弹窗确认
function handleModalOk() {
  // 验证必填项
  if (!formState.value.masterField || !formState.value.slaveTableName || !formState.value.slaveField) {
    return
  }

  const newRelation: TableRelation = { ...formState.value }

  if (isEdit.value && editIndex.value >= 0) {
    // 编辑模式
    const newRelations = [...relations.value]
    newRelations[editIndex.value] = newRelation
    relations.value = newRelations
  }
  else {
    // 新增模式
    relations.value = [...relations.value, newRelation]
  }

  modalVisible.value = false
}

// 方法：弹窗取消
function handleModalCancel() {
  modalVisible.value = false
}

// 方法：自动识别关系
function autoDetectRelations() {
  // 简单的自动识别逻辑：查找从表中包含主表名+_id的字段
  const detectedRelations: TableRelation[] = []

  const masterNameLower = props.currentTableName.toLowerCase()
  const possibleFkNames = [
    `${masterNameLower}_id`,
    `${masterNameLower}Id`,
    `${masterNameLower}_fk`,
    'parent_id',
  ]

  props.selectedTables.forEach((tableName) => {
    if (tableName === props.currentTableName)
      return

    const fields = props.tableFieldsMap[tableName] || []
    const pkField = props.currentTableFields.find(f => f.isPk)

    fields.forEach((field) => {
      const fieldNameLower = field.columnName.toLowerCase()
      if (possibleFkNames.some(fk => fieldNameLower === fk || fieldNameLower.endsWith(`_${fk}`))) {
        detectedRelations.push({
          relationType: 'oneToMany',
          slaveTableName: tableName,
          slaveClassName: toCamelCase(tableName.replace(/^(sys_|t_|tb_)/, ''), true),
          masterField: pkField?.columnName || 'id',
          slaveField: field.columnName,
          propertyName: `${toCamelCase(tableName.replace(/^(sys_|t_|tb_)/, ''), false)}List`,
          cascadeDelete: false,
          lazyLoad: true,
        })
      }
    })
  })

  if (detectedRelations.length > 0) {
    relations.value = [...relations.value, ...detectedRelations]
  }
}

// 方法：过滤选项
function filterOption(input: string, option: any) {
  return option.label.toLowerCase().includes(input.toLowerCase())
}

// 工具方法：转换为驼峰命名
function toCamelCase(str: string, capitalizeFirst: boolean): string {
  if (!str)
    return str

  const parts = str.split(/[_-]/)
  const result = parts.map((part, index) => {
    if (index === 0 && !capitalizeFirst) {
      return part.toLowerCase()
    }
    return part.charAt(0).toUpperCase() + part.slice(1).toLowerCase()
  }).join('')

  return result
}

// 监听：当从表字段选项变化时，如果当前选中的字段不存在，清空它
watch(slaveFieldOptions, (newOptions) => {
  if (formState.value.slaveField && newOptions.length > 0) {
    const exists = newOptions.some(opt => opt.value === formState.value.slaveField)
    if (!exists) {
      formState.value.slaveField = ''
    }
  }
})
</script>

<template>
  <div class="table-relation-designer">
    <!-- 工具栏 -->
    <div class="toolbar">
      <a-space>
        <a-button type="primary" @click="showAddRelationModal">
          <PlusOutlined /> 添加关系
        </a-button>
        <a-button :disabled="selectedTables.length < 2" @click="autoDetectRelations">
          <ApartmentOutlined /> 自动识别
        </a-button>
      </a-space>
      <a-alert
        v-if="selectedTables.length < 2"
        message="请至少选择2个表才能配置关系"
        type="warning"
        show-icon
        style="margin-left: 16px; display: inline-block"
      />
    </div>

    <!-- 关系列表 -->
    <div class="relation-list">
      <a-empty v-if="!relations || relations.length === 0" description="暂无表关系配置" />

      <div v-else class="relation-cards">
        <a-card
          v-for="(relation, index) in relations"
          :key="index"
          size="small"
          class="relation-card"
          :title="getRelationTitle(relation)"
        >
          <template #extra>
            <a-space>
              <a-button type="link" size="small" @click="editRelation(relation, index)">
                编辑
              </a-button>
              <a-button type="link" danger size="small" @click="removeRelation(index)">
                删除
              </a-button>
            </a-space>
          </template>

          <div class="relation-detail">
            <div class="table-row">
              <div class="table-box master">
                <span class="label">主表</span>
                <span class="name">{{ relation.masterTableName || currentTableName }}</span>
                <span class="field">{{ relation.masterField }}</span>
              </div>
              <div class="relation-arrow">
                <ArrowRightOutlined />
                <span class="relation-type">{{ getRelationTypeLabel(relation.relationType) }}</span>
              </div>
              <div class="table-box slave">
                <span class="label">从表</span>
                <span class="name">{{ relation.slaveTableName }}</span>
                <span class="field">{{ relation.slaveField }}</span>
              </div>
            </div>
            <div class="property-row">
              <a-tag v-if="relation.propertyName">
                属性名: {{ relation.propertyName }}
              </a-tag>
              <a-tag v-if="relation.cascadeDelete" color="red">
                级联删除
              </a-tag>
              <a-tag v-if="relation.lazyLoad" color="blue">
                懒加载
              </a-tag>
            </div>
          </div>
        </a-card>
      </div>
    </div>

    <!-- 添加/编辑关系弹窗 -->
    <a-modal
      v-model:open="modalVisible"
      :title="isEdit ? '编辑关系' : '添加关系'"
      width="600px"
      @ok="handleModalOk"
      @cancel="handleModalCancel"
    >
      <a-form :model="formState" layout="vertical">
        <a-form-item label="关系类型" required>
          <a-radio-group v-model:value="formState.relationType">
            <a-radio-button value="oneToOne">
              一对一
            </a-radio-button>
            <a-radio-button value="oneToMany">
              一对多
            </a-radio-button>
          </a-radio-group>
        </a-form-item>

        <a-row :gutter="16">
          <a-col :span="12">
            <a-form-item label="主表字段" required>
              <a-select
                v-model:value="formState.masterField"
                :options="currentTableFieldOptions"
                placeholder="选择主表字段"
                show-search
                :filter-option="filterOption"
              />
            </a-form-item>
          </a-col>
          <a-col :span="12">
            <a-form-item label="从表" required>
              <a-select
                v-model:value="formState.slaveTableName"
                :options="slaveTableOptions"
                placeholder="选择从表"
                @change="onSlaveTableChange"
              />
            </a-form-item>
          </a-col>
        </a-row>

        <a-row :gutter="16">
          <a-col :span="12">
            <a-form-item label="从表外键字段" required>
              <a-select
                v-model:value="formState.slaveField"
                :options="slaveFieldOptions"
                placeholder="选择或输入字段名"
                allow-create
                show-search
              />
            </a-form-item>
          </a-col>
          <a-col :span="12">
            <a-form-item label="属性名">
              <a-input
                v-model:value="formState.propertyName"
                placeholder="例如：orderItems"
              />
              <div class="form-help">
                实体类中从表集合的属性名
              </div>
            </a-form-item>
          </a-col>
        </a-row>

        <a-form-item>
          <a-checkbox v-model:checked="formState.cascadeDelete">
            级联删除
          </a-checkbox>
          <div class="form-help">
            删除主表数据时同时删除关联的从表数据
          </div>
        </a-form-item>

        <a-form-item>
          <a-checkbox v-model:checked="formState.lazyLoad">
            懒加载
          </a-checkbox>
          <div class="form-help">
            查询主表时不自动加载从表数据
          </div>
        </a-form-item>
      </a-form>
    </a-modal>
  </div>
</template>

<style scoped>
.table-relation-designer {
  padding: 16px;
}

.toolbar {
  margin-bottom: 16px;
  display: flex;
  align-items: center;
}

.relation-list {
  min-height: 200px;
}

.relation-cards {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.relation-card {
  border: 1px solid #f0f0f0;
}

.relation-detail {
  padding: 8px 0;
}

.table-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 12px;
}

.table-box {
  flex: 1;
  padding: 12px;
  border-radius: 6px;
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.table-box.master {
  background-color: #e6f7ff;
  border: 1px solid #91d5ff;
}

.table-box.slave {
  background-color: #f6ffed;
  border: 1px solid #b7eb8f;
}

.table-box .label {
  font-size: 12px;
  color: #666;
}

.table-box .name {
  font-weight: 500;
  font-size: 14px;
}

.table-box .field {
  font-size: 12px;
  color: var(--brand);
  font-family: monospace;
}

.relation-arrow {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4px;
  color: #666;
}

.relation-arrow .relation-type {
  font-size: 12px;
  white-space: nowrap;
}

.property-row {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}

.form-help {
  font-size: 12px;
  color: #999;
  margin-top: 4px;
}
</style>

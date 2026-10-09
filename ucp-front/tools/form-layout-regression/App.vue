<script setup lang="ts">
import { provide, ref } from 'vue'
import zhCN from 'ant-design-vue/es/locale/zh_CN'
import { antdTheme } from '../../src/theme/antd-theme'
import { nocodePlatformKey, type NocodePlatform } from '../../src/nocode/platform'
import ObjectSharingPanel from '../../src/views/nocode/components/ObjectSharingPanel.vue'
import ObjectGrantFields from '../../src/views/nocode/components/ObjectGrantFields.vue'
import OsModalForm from '../../src/components/ucp-modal-form/OsModalForm.vue'
import MemberSelect from '../../src/components/MemberSelect.vue'
import RecordQueryField from '../../src/views/nocode/application/components/RecordQueryField.vue'
import RecordForm from '../../src/views/nocode/application/components/RecordForm.vue'
import OsConditionGroupRenderer from '../../src/components/ucp-table-page/OsConditionGroupRenderer.vue'
import { FieldType } from '../../src/types/nocode/enums'
import type { PublishedDefinition } from '../../src/types/nocode/application'
import type { ObjectGrant } from '../../src/types/nocode/authorization'

// 本地组件回归夹具；所有读写使用内存数据，不连接开发库或正式路由。
const labels = [
  '决算月',
  '法人番号',
  '管理状态',
  '法人代表',
  '邮编',
  '假名',
  '管理类型',
  '英文名',
  '委托类型',
  '注册资本币种',
  '联系邮箱',
  '所有者',
  '负责人',
  '正式名称',
  '成立日期'
]
const options = Array.from({ length: 45 }, (_, i) => ({
  value: `field-${i}`,
  label: labels[i] || `用于验证完整名称与换行的扩展业务字段${i + 1}`
}))
const values = options.map(o => o.value)
const definition = {
  objectName: '公司',
  fields: options.map(o => ({ id: o.value, key: o.value, name: o.label, type: FieldType.TEXT })),
  details: Array.from({ length: 12 }, (_, i) => ({ id: `detail-${i}`, name: `内部明细${i + 1}`, state: 'ACTIVE' })),
  relations: Array.from({ length: 10 }, (_, i) => ({
    id: `relation-${i}`,
    name: `多对多关系${i + 1}`,
    kind: 'MANY_TO_MANY'
  }))
} as PublishedDefinition
const initial: ObjectGrant = {
  objectId: 'company',
  actions: ['READ', 'CREATE', 'UPDATE', 'DELETE', 'IMPORT', 'EXPORT'],
  scope: 'ALL',
  readFields: [...values],
  writeFields: values.slice(0, 20),
  readDetails: definition.details.map(d => d.id!),
  writeDetails: definition.details.slice(0, 4).map(d => d.id!),
  readRelations: definition.relations.map(r => r.id!),
  writeRelations: definition.relations.slice(0, 3).map(r => r.id!)
}
const clone = <T,>(value: T): T => JSON.parse(JSON.stringify(value))
const grant = ref(clone(initial))
const ceiling = { ...clone(initial), readFields: values.slice(0, 5), writeFields: values.slice(0, 3) }
const saved = ref<ObjectGrant | null>(clone(initial))
const saveCount = ref(0)
const failSave = ref(false)
provide(nocodePlatformKey, {
  applications: {
    objectSharing: async () => [
      {
        objectId: 'company',
        applicationId: 'app-company',
        applicationName: '公司管理',
        revision: 1,
        permission: clone(saved.value),
        reason: '已有共享授权'
      }
    ],
    sharingTargets: async () => [{ id: 'app-company', name: '公司管理' }],
    sharingDefinition: async () => ({ definition }),
    saveObjectSharing: async (input: { permission: ObjectGrant | null }) => {
      if (failSave.value) throw new Error('回归夹具：保存失败，请重试')
      saved.value = clone(input.permission)
      saveCount.value++
    }
  }
} as unknown as NocodePlatform)
const section = ref('sharing')
const selected = ref([...values])
const empty = ref([])
const record = ref({ selection: [...values] })
const searchGroup = ref({
  id: 'group',
  type: 'group' as const,
  logic: 'AND' as const,
  items: [
    { id: 'condition', type: 'condition' as const, field: 'selection', operator: 'in' as const, value: [...values] }
  ]
})
const single = ref(values[0])
const modal = ref(false)
const displayMode = ref<'modal' | 'drawer' | 'fullscreen'>('modal')
</script>

<template>
  <a-config-provider :theme="antdTheme" :locale="zhCN">
    <main class="regression-page">
      <nav>
        <a-button
          v-for="item in ['sharing', 'controls', 'readonly', 'restricted', 'modal']"
          :key="item"
          @click="section = item"
        >
          {{ item }}
        </a-button>
      </nav>
      <template v-if="section === 'sharing'">
        <ObjectSharingPanel object-id="company" />
        <p>
          内存保存次数：
          <output data-testid="save-count">{{ saveCount }}</output>
        </p>
        <a-checkbox v-model:checked="failSave">模拟保存失败</a-checkbox>
      </template>
      <template v-if="section === 'controls'">
        <section data-testid="generated-form">
          <RecordForm
            v-model="record"
            :fields="
              [
                {
                  id: 'selection',
                  key: 'selection',
                  code: 'selection',
                  name: '生成的多选字段',
                  type: FieldType.MULTI_SELECT
                }
              ] as any
            "
            :options="
              { selection: { options: options.map(o => ({ code: o.value, label: o.label, disabled: false })) } } as any
            "
            :model="{ writable: true, generatedKey: true, keyFieldId: null, keyType: 'text' }"
            creating
          />
          <p class="following-field">生成表单后续内容</p>
        </section>
        <section data-testid="advanced-query">
          <OsConditionGroupRenderer
            :group="searchGroup"
            :fields="[{ field: 'selection', label: '高级查询字段', type: 'select', options }]"
            :field-options="[{ value: 'selection', label: '高级查询字段' }]"
          />
        </section>
        <a-form layout="vertical" class="control-grid">
          <a-form-item
            v-for="size in ['small', 'middle', 'large'] as const"
            :key="size"
            :label="`${size} 多选`"
            :data-testid="`multiple-${size}`"
          >
            <a-select v-model:value="selected" mode="multiple" :size="size" :options="options" />
            <p class="following-field">后续内容不得与选项重叠</p>
          </a-form-item>
          <a-form-item label="标签" data-testid="tags">
            <a-select v-model:value="selected" mode="tags" :options="options" />
            <p class="following-field">后续内容</p>
          </a-form-item>
          <a-form-item label="树多选" data-testid="tree">
            <a-tree-select
              v-model:value="selected"
              multiple
              :tree-data="options.map(o => ({ title: o.label, value: o.value }))"
            />
            <p class="following-field">后续内容</p>
          </a-form-item>
          <a-form-item label="禁用多选" data-testid="disabled">
            <a-select v-model:value="selected" mode="multiple" disabled :options="options" />
            <p class="following-field">后续内容</p>
          </a-form-item>
          <a-form-item label="空多选" data-testid="empty">
            <a-select v-model:value="empty" mode="multiple" :options="options" placeholder="请选择" />
          </a-form-item>
          <a-form-item label="成员选择" data-testid="member">
            <MemberSelect v-model:value="selected" mode="multiple" :options="options" />
            <p class="following-field">后续内容</p>
          </a-form-item>
          <a-form-item label="运行端查询" data-testid="query">
            <RecordQueryField
              v-model="selected"
              :field="{ id: 'query', key: 'query', name: '查询', type: FieldType.MULTI_SELECT } as any"
              :choices="options"
            />
            <p class="following-field">后续内容</p>
          </a-form-item>
          <a-form-item
            v-for="size in ['small', 'middle', 'large'] as const"
            :key="`single-${size}`"
            :label="`${size} 单选`"
            :data-testid="`single-${size}`"
          >
            <a-select v-model:value="single" :size="size" :options="options" />
          </a-form-item>
          <a-form-item label="多行备注" data-testid="textarea">
            <a-textarea :rows="4" value="第一行&#10;第二行&#10;第三行&#10;第四行" />
          </a-form-item>
        </a-form>
      </template>
      <template v-if="section === 'readonly' || section === 'restricted'">
        <ObjectGrantFields
          v-model="grant"
          :definition="definition"
          :readonly="section === 'readonly'"
          :ceiling="section === 'restricted' ? ceiling : undefined"
        />
        <output data-testid="grant-value">{{ JSON.stringify(grant) }}</output>
      </template>
      <template v-if="section === 'modal'">
        <a-button @click="modal = true">打开底座长表单</a-button>
        <OsModalForm
          :open="modal"
          title="底座长表单"
          :width="760"
          :height="1200"
          :display-mode="displayMode"
          layout="vertical"
          @cancel="modal = false"
          @ok="modal = false"
          @display-mode-change="displayMode = $event"
        >
          <template #formItems>
            <a-form-item v-for="i in 18" :key="i" :label="`字段${i}`">
              <a-select v-model:value="selected" mode="multiple" :options="options" />
            </a-form-item>
          </template>
        </OsModalForm>
      </template>
    </main>
  </a-config-provider>
</template>

<style scoped>
.regression-page {
  max-width: 1280px;
  margin: auto;
  padding: 24px;
  background: white;
}
nav {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 20px;
}
.control-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 16px;
}
.following-field {
  margin-top: 8px;
}
output[data-testid='grant-value'] {
  display: block;
  overflow-wrap: anywhere;
}
@media (max-width: 600px) {
  .control-grid {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>

<script lang="ts" setup>
import { toRefs, watch } from 'vue'

import { IconifyIcon } from '@vben/icons'

import { Alert, Button, Col, FormItem, Input, Row, Select, SelectOption } from 'ant-design-vue'

import { useFormFields } from '../../../helpers'
import HttpRequestParamSetting from './http-request-param-setting.vue'

defineOptions({ name: 'HttpRequestSetting' })

const props = defineProps({
  setting: {
    type: Object,
    required: true
  },
  responseEnable: {
    type: Boolean,
    required: true
  },
  formItemPrefix: {
    type: String,
    required: true
  }
})

const emits = defineEmits(['update:setting'])

const { setting } = toRefs(props)

watch(
  () => setting,
  val => {
    emits('update:setting', val)
  }
)

/** 流程表单字段 */
const formFields = useFormFields()

/** 添加 HTTP 请求返回值设置项 */
function addHttpResponseSetting(responseSetting: Record<string, string>[]) {
  responseSetting.push({
    key: '',
    value: ''
  })
}

/** 删除 HTTP 请求返回值设置项 */
function deleteHttpResponseSetting(responseSetting: Record<string, string>[], index: number | string) {
  responseSetting.splice(Number(index), 1)
}
</script>
<template>
  <FormItem>
    <Alert :closable="false" message="仅支持 POST 请求，以请求体方式接收参数" show-icon type="warning" />
  </FormItem>
  <!-- 请求地址-->
  <FormItem
    :label-col="{ span: 24 }"
    :name="[formItemPrefix, 'url']"
    :rules="{
      required: true,
      message: '请求地址不能为空',
      trigger: ['blur', 'change']
    }"
    :wrapper-col="{ span: 24 }"
    label="请求地址"
  >
    <Input v-model:value="setting.url" placeholder="请输入请求地址" />
  </FormItem>
  <!-- 请求头，请求体设置-->
  <HttpRequestParamSetting :bind="formItemPrefix" :body="setting.body" :header="setting.header" />
  <!-- 返回值设置-->
  <div v-if="responseEnable">
    <FormItem :label-col="{ span: 24 }" :wrapper-col="{ span: 24 }" label="返回值">
      <Alert :closable="false" message="通过请求返回值, 可以修改流程表单的值" show-icon type="warning" />
    </FormItem>
    <FormItem :wrapper-col="{ span: 24 }">
      <Row v-for="(item, index) in setting.response" :key="index" :gutter="8" class="mb-2">
        <Col :span="10">
          <FormItem
            :name="[formItemPrefix, 'response', index, 'key']"
            :rules="{
              required: true,
              message: '表单字段不能为空',
              trigger: ['blur', 'change']
            }"
          >
            <Select v-model:value="item.key" allow-clear placeholder="请选择表单字段">
              <SelectOption
                v-for="(field, fIdx) in formFields"
                :key="fIdx"
                :disabled="!field.required"
                :label="field.title"
                :value="field.field"
              >
                {{ field.title }}
              </SelectOption>
            </Select>
          </FormItem>
        </Col>
        <Col :span="12">
          <FormItem
            :name="[formItemPrefix, 'response', index, 'value']"
            :rules="{
              required: true,
              message: '请求返回字段不能为空',
              trigger: ['blur', 'change']
            }"
          >
            <Input v-model:value="item.value" placeholder="请求返回字段" />
          </FormItem>
        </Col>
        <Col :span="2">
          <div class="flex h-8 items-center">
            <IconifyIcon
              class="size-4 cursor-pointer text-red-500"
              icon="lucide:trash-2"
              @click="deleteHttpResponseSetting(setting.response, index)"
            />
          </div>
        </Col>
      </Row>
      <Button class="flex items-center" type="link" @click="addHttpResponseSetting(setting.response!)">
        <template #icon>
          <IconifyIcon class="size-4" icon="lucide:plus" />
        </template>
        添加一行
      </Button>
    </FormItem>
  </div>
</template>

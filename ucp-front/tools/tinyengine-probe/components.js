import { defineComponent, h, ref, onMounted, watch } from 'vue'
import Antd, { Card, Tabs, Row, Col, Table, Button, Alert } from 'ant-design-vue'
import formCreate from '@form-create/ant-design-vue'
import { createRuntimeApi } from '../../src/api/nocode/runtime'
import 'ant-design-vue/dist/reset.css'
import './probe.css'

/** 验证适配器仅开放查询。正式集成使用 NocodePlatform，绝不引入新登录或权限后端。 */
const request = async (url, method, body, params) => {
  const query = params ? `?${new URLSearchParams(params)}` : ''
  const response = await fetch(`/api${url}${query}`, {
    method,
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${sessionStorage.getItem('tinyengine-probe-token') || ''}`
    },
    ...(body ? { body: JSON.stringify(body) } : {})
  })
  const result = await response.json()
  if (result.code !== 0) throw new Error(result.msg || '查询失败')
  return result.data
}
const runtime = createRuntimeApi({
  get: (url, config) => request(url, 'GET', null, config?.params),
  post: (url, body) => {
    if (url !== '/nocode/runtime/page') throw new Error('验证工具只允许查询业务记录')
    return request(url, 'POST', body)
  }
})

export const OsBusinessList = defineComponent({
  name: 'OsBusinessList',
  props: { title: String, applicationId: String, objectId: String },
  setup(props) {
    const rows = ref([]),
      columns = ref([]),
      error = ref(''),
      loading = ref(false),
      clicks = ref(0)
    async function load() {
      if (!sessionStorage.getItem('tinyengine-probe-token')) {
        error.value = '尚未连接开发库（验证程序通过现有登录接口注入临时会话）'
        return
      }
      loading.value = true
      try {
        const model = await runtime.model(props.applicationId, props.objectId)
        const result = await runtime.page({
          applicationId: props.applicationId,
          objectId: props.objectId,
          pageNo: 1,
          pageSize: 10,
          descending: false
        })
        const fields = model.object.fields.filter(field => model.permissions.readFields.includes(field.id)).slice(0, 4)
        columns.value = [
          { title: '记录 ID', dataIndex: 'id' },
          ...fields.map(field => ({
            title: field.name,
            key: field.id,
            dataIndex: ['values', field.id],
            customRender: ({ text }) => (Array.isArray(text) ? text.join(' / ') : String(text ?? ''))
          }))
        ]
        rows.value = result.list
        error.value = ''
      } catch (e) {
        error.value = e.message
      } finally {
        loading.value = false
      }
    }
    onMounted(load)
    return () =>
      h('section', { class: 'os-business-list', 'data-business-clicks': clicks.value }, [
        h('div', { class: 'block-heading' }, [
          h('strong', props.title),
          h(
            Button,
            {
              onClick: () => {
                clicks.value++
                void load()
              }
            },
            () => '刷新订单'
          )
        ]),
        error.value ? h(Alert, { type: 'warning', message: error.value }) : null,
        h(Table, {
          columns: columns.value,
          dataSource: rows.value,
          rowKey: 'id',
          loading: loading.value,
          pagination: false,
          size: 'small',
          scroll: { x: 450 }
        }),
        h('small', `已读取 ${rows.value.length} 条当前开发库记录 · 按钮执行 ${clicks.value} 次`)
      ])
  }
})

export const OsBusinessForm = defineComponent({
  name: 'OsBusinessForm',
  props: { title: String },
  setup(props) {
    const values = ref({}),
      submitted = ref(null)
    const rule = [
      {
        type: 'input',
        field: 'orderName',
        title: '订单名称',
        props: { placeholder: '输入订单名称' },
        validate: [{ required: true, message: '请填写订单名称' }]
      },
      { type: 'inputNumber', field: 'quantity', title: '数量', value: 1, props: { min: 1 } }
    ]
    return () =>
      h('section', { class: 'os-business-form' }, [
        h('strong', props.title),
        h(formCreate, {
          rule,
          option: { submitBtn: { innerText: '验证表单提交' }, resetBtn: false },
          modelValue: values.value,
          'onUpdate:modelValue': value => {
            values.value = value
          },
          onSubmit: value => {
            submitted.value = value
          }
        }),
        submitted.value ? h('pre', { class: 'form-result' }, JSON.stringify(submitted.value)) : null
      ])
  }
})

/** DSL 的 activeKey 是初始配置；运行时切换保留在组件状态中，不改写已保存页面。 */
const OsTabs = defineComponent({
  name: 'OsTabs',
  inheritAttrs: false,
  props: { activeKey: String },
  setup(props, { attrs, slots }) {
    const activeKey = ref(props.activeKey)
    watch(
      () => props.activeKey,
      value => {
        activeKey.value = value
      }
    )
    return () =>
      h(
        Tabs,
        {
          ...attrs,
          activeKey: activeKey.value,
          'onUpdate:activeKey': value => {
            activeKey.value = value
          }
        },
        slots
      )
  }
})

export const components = {
  OsCard: Card,
  OsTabs,
  OsTabPane: Tabs.TabPane,
  OsRow: Row,
  OsCol: Col,
  OsBusinessList,
  OsBusinessForm,
  OsText: defineComponent({ props: { text: String }, setup: props => () => h('p', props.text) })
}

export function installComponents(app) {
  app.use(Antd).use(formCreate)
  Object.entries(components).forEach(([name, component]) => app.component(name, component))
}

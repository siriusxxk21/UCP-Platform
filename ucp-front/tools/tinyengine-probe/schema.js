export const STORAGE_KEY = 'richuang.tinyengine-probe.schema.v1'
export const node = (id, componentName, props = {}, children = []) => ({ id, componentName, props, children })

/** 与原型相同的关键结构：根容器 → 页签 → 分栏/卡片 → 列表和表单。 */
export function initialSchema() {
  return {
    componentName: 'Page',
    fileName: 'OrderContainerProbe',
    props: {},
    state: {},
    methods: {},
    css: '',
    lifeCycles: {},
    inputs: [],
    outputs: [],
    children: [
      node('page_root', 'OsCard', { title: '订单经营工作台' }, [
        node('tabs', 'OsTabs', { activeKey: 'all' }, [
          node('tab_all', 'OsTabPane', { key: 'all', tab: '全部订单' }, [
            node('row', 'OsRow', { gutter: 16 }, [
              node('list_column', 'OsCol', { span: 16 }, [
                node('list_card', 'OsCard', { title: '订单列表' }, [
                  node('orders', 'OsBusinessList', { title: '订单业务列表', applicationId: '41', objectId: '958' })
                ])
              ]),
              node('form_column', 'OsCol', { span: 8 }, [
                node('form_card', 'OsCard', { title: 'form-create 表单' }, [
                  node('order_form', 'OsBusinessForm', { title: '订单录入（验证暂存）' })
                ])
              ])
            ])
          ]),
          node('tab_archived', 'OsTabPane', { key: 'archived', tab: '归档订单' }, [
            node('archived_card', 'OsCard', { title: '归档页签内容' }, [
              node('archived_text', 'OsText', { text: '这是第二个页签，验证切换和保存恢复。' })
            ])
          ])
        ])
      ])
    ]
  }
}

export function readSchema() {
  const saved = localStorage.getItem(STORAGE_KEY)
  return saved ? JSON.parse(saved) : initialSchema()
}

const field = (property, title, type = 'string') => ({
  property,
  type,
  label: { text: { zh_CN: title } },
  cols: 12,
  widget: { component: type === 'number' ? 'NumberConfigurator' : 'InputConfigurator', props: {} }
})
const descriptions = [
  ['OsCard', '卡片容器', true, [field('title', '标题')]],
  ['OsTabs', '页签容器', true, [field('activeKey', '默认页签')]],
  ['OsTabPane', '单个页签', true, [field('key', '页签标识'), field('tab', '页签名称')]],
  ['OsRow', '分栏容器', true, [field('gutter', '列间距', 'number')]],
  ['OsCol', '分栏', true, [field('span', '列宽（24 格）', 'number')]],
  [
    'OsBusinessList',
    '业务列表',
    false,
    [field('title', '标题'), field('applicationId', '应用 ID'), field('objectId', '对象 ID')]
  ],
  ['OsBusinessForm', '业务表单', false, [field('title', '标题')]],
  ['OsText', '说明文字', false, [field('text', '文字')]]
]

/** 使用公开物料协议；列表/表单为叶子，容器保持结构语义。 */
export const materials = {
  components: descriptions.map(([component, title, container, properties]) => ({
    component,
    name: { zh_CN: title },
    icon: 'container',
    configure: {
      isContainer: container,
      styles: true,
      nestingRule: {
        ...(component === 'OsTabs' ? { childWhitelist: ['OsTabPane'] } : {}),
        ...(component === 'OsTabPane' ? { parentWhitelist: ['OsTabs'] } : {}),
        ...(component === 'OsRow' ? { childWhitelist: ['OsCol'] } : {}),
        ...(component === 'OsCol' ? { parentWhitelist: ['OsRow'] } : {})
      }
    },
    schema: {
      properties: [{ label: { zh_CN: '基础属性' }, content: properties }],
      events: {},
      slots: container ? { default: { label: { zh_CN: '内容' } } } : {}
    }
  })),
  snippets: [
    {
      group: 'component',
      label: { zh_CN: 'OS 业务组件' },
      children: descriptions.map(([componentName, title]) => ({
        snippetName: componentName,
        name: { zh_CN: title },
        icon: 'container',
        schema: { componentName, props: { title, text: title }, children: [] }
      }))
    }
  ]
}

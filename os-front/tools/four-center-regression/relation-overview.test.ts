import { expect, it } from 'vitest'
import { createApp, h } from 'vue'
import Antd from 'ant-design-vue'
import ObjectRelationOverview from '@/views/nocode/components/ObjectRelationOverview.vue'

it('关系图把明细引用放在对应明细下，并明确内部明细与独立对象的保存边界', () => {
  const host = document.createElement('div')
  const app = createApp({
    render: () =>
      h(ObjectRelationOverview, {
        name: '采购单',
        targetNames: { vendor: '供应商', product: '商品' },
        details: [{ id: 'lines', code: 'lines', name: '采购明细', state: 'ACTIVE' }] as any,
        relations: [
          { id: 'vendor', code: 'vendor', name: '采购供应商', kind: 'REFERENCE', targetObjectId: 'vendor' },
          {
            id: 'product',
            code: 'product',
            name: '明细商品',
            kind: 'REFERENCE',
            targetObjectId: 'product',
            sourceDetailId: 'lines'
          }
        ] as any
      })
  })
  app.use(Antd)
  app.mount(host)
  expect(host.querySelector('[data-source="lines"]')?.textContent).toContain('明细商品')
  expect(host.querySelector('[data-source="main"]')?.textContent).not.toContain('明细商品')
  expect(host.textContent).toContain('随主记录一起保存和校验')
  expect(host.textContent).toContain('两边独立保存')
  app.unmount()
})

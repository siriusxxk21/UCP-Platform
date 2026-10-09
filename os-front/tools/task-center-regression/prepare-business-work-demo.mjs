/** 当前开发环境的业务视图/标准工时体验夹具；只新建随机前缀资源，登记后保留供 UI 验收。 */
import { FormAcceptance } from '../data-form-regression/http-acceptance.mjs'
import { writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'

const ac = new FormAcceptance(undefined, '.work/task-business-work-demo')
await ac.login()
const object = await ac.object('rooms', '房间办理（标准工时验收）', [
  ac.field('name', 'TEXT', '房间号'),
  ac.field('wifi', 'TEXT', 'WiFi 密码'),
  ac.field('quantity', 'INTEGER', '配置设备数量')
])
const resources = []
for (const [key, name, codes] of [
  ['wifi', '房间 WiFi 配置', ['name', 'wifi']],
  ['devices', '房间设备登记', ['name', 'quantity']]
]) {
  resources.push(
    {
      id: `${key}_form`,
      code: `${key}_form`,
      kind: 'FORM',
      name: `${name}表单`,
      config: {
        objectId: object.objectId,
        detailIds: [],
        options: { layout: 'vertical', submitText: '保存业务数据' },
        nodes: codes.map(code => ({
          id: `${key}_${code}`,
          type: 'FIELD',
          fieldId: object.ids[code],
          children: [],
          span: 12
        }))
      }
    },
    {
      id: `${key}_view`,
      code: `${key}_view`,
      kind: 'VIEW',
      name,
      config: {
        objectId: object.objectId,
        fieldIds: codes.map(code => object.ids[code]),
        equal: {},
        formId: `${key}_form`,
        pageSize: 10
      }
    }
  )
}
ac.app = await ac.api('/nocode/application/save', {
  id: null,
  expectedRevision: null,
  code: `${ac.prefix}_work_demo`,
  name: `业务视图工时验收 ${ac.prefix}`,
  description: '2026-10-06 开发环境专用体验数据，不含真实业务',
  definition: {
    objects: [{ objectId: object.objectId, versionNo: object.versionNo, checksum: object.checksum }],
    resources
  }
})
ac.owned.applications.push({ id: ac.app.application.id, code: ac.app.application.code })
await ac.persist()
await ac.share(object, ac.grant(object, { actions: ['READ', 'CREATE', 'UPDATE', 'DELETE'] }))
await ac.api('/nocode/application/publish', {
  id: ac.app.application.id,
  expectedRevision: ac.app.application.revision,
  reason: '标准工时 UI 验收夹具'
})
await writeFile(
  resolve(ac.output, 'fixture.json'),
  JSON.stringify(
    {
      applicationId: ac.app.application.id,
      applicationName: ac.app.application.name,
      objectId: object.objectId,
      fields: object.ids
    },
    null,
    2
  )
)
console.log(
  JSON.stringify({
    applicationId: ac.app.application.id,
    applicationName: ac.app.application.name,
    objectId: object.objectId
  })
)

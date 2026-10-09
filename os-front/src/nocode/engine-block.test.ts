import { describe, expect, it } from 'vitest'
import { pageNodes, pageSchema } from './page-schema'
import { NodeKind, uiNode } from '@/types/nocode/application-ui'
import { ENGINE_SANDBOX, engineFrameSrc, engineRequest, engineUrlError, parseEngineConfig } from './engine-block'

const config = {
  engineUrl: '/engine01/',
  materials: { objectId: 'lib', fields: { name: 'f-name', unitPrice: 'f-price' } },
  bom: { objectId: 'bom', recordFieldId: 'f-site', keyFieldId: 'f-key', fields: { quantity: 'f-qty' } }
}

describe('设计引擎区块（laneEG）', () => {
  it('keeps the engine configuration through the designer schema and never binds a resource', () => {
    const node = uiNode(NodeKind.ENGINE, { text: '设计', engine: config })
    const [restored] = pageNodes(pageSchema([node]))
    expect(restored).toMatchObject({ type: 'ENGINE', resourceId: null, engine: config, text: '设计' })
    expect(pageSchema([node]).children[0]).toMatchObject({ componentName: 'OsEngine' })
  })

  it('accepts only same-site relative engine paths', () => {
    expect(engineUrlError('/engine01/')).toBeNull()
    expect(engineUrlError('')).toBeNull()
    for (const bad of [
      'https://evil.example/',
      '//evil.example/x',
      '/engine01/../api',
      'javascript:alert(1)',
      'engine01'
    ])
      expect(engineUrlError(bad)).toMatch('站内路径')
    expect(() => engineFrameSrc('https://evil.example/', '1/2/3')).toThrow('站内路径')
  })

  it('puts the project key, never the token, into the iframe address', () => {
    const src = engineFrameSrc('/engine01/', '101/202/303')
    expect(src).toBe('/engine01/?embed=1&project=101%2F202%2F303')
    expect(src).not.toMatch(/token/i)
  })

  it('sandboxes the engine without same-origin, top navigation or popups', () => {
    expect(ENGINE_SANDBOX.split(' ')).toEqual(['allow-scripts', 'allow-downloads', 'allow-modals', 'allow-forms'])
    expect(ENGINE_SANDBOX).not.toMatch(/same-origin|top-navigation|popups/)
  })

  it('answers token requests only from its own iframe window and project', () => {
    const frame = {} as Window
    const other = {} as Window
    const ready = { type: 'engine01:ready', project: '1/2/3' }
    expect(engineRequest({ source: frame, data: ready }, frame, '1/2/3')).toEqual(ready)
    expect(engineRequest({ source: other, data: ready }, frame, '1/2/3')).toBeNull()
    expect(engineRequest({ source: frame, data: { ...ready, project: '1/2/4' } }, frame, '1/2/3')).toBeNull()
    expect(engineRequest({ source: frame, data: { type: 'engine01:token' } }, frame, '1/2/3')).toBeNull()
    expect(engineRequest({ source: frame, data: ready }, null, '1/2/3')).toBeNull()
  })

  it('rejects malformed configuration text', () => {
    expect(parseEngineConfig(null)).toBeNull()
    expect(() => parseEngineConfig('[1]')).toThrow('格式错误')
  })
})

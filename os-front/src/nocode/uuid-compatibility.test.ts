import { webcrypto } from 'node:crypto'
import { fileURLToPath } from 'node:url'
import { runInNewContext } from 'node:vm'
import { build } from 'vite'
import { expect, it } from 'vitest'

it('creates drafts and layout nodes when HTTP does not expose crypto.randomUUID', async () => {
  // 打包浏览器入口，避免 Node 的 uuid 实现掩盖线上浏览器兼容问题。
  const entry = fileURLToPath(new URL('./uuid-compatibility-entry.js', import.meta.url))
  const result = await build({
    configFile: false,
    logLevel: 'silent',
    define: { 'process.env.NODE_ENV': JSON.stringify('production') },
    resolve: { alias: { '@': fileURLToPath(new URL('../', import.meta.url)) } },
    plugins: [
      {
        name: 'uuid-compatibility',
        resolveId: id => (id === entry ? entry : undefined),
        load: id =>
          id === entry
            ? `
              import { newDraft, newField } from '@/nocode/object-draft'
              import { uiNode, NodeKind } from '@/types/nocode/application-ui'
              import { rulesToNodes } from '@/nocode/application-ui'
              import { pageNodes } from '@/nocode/page-schema'
              export function create() {
                const draft = newDraft()
                return {
                  draft,
                  ids: [
                    draft.fields[0].key.slice(4),
                    newField(1).key.slice(4),
                    uiNode(NodeKind.TEXT).id,
                    rulesToNodes([{ type: 'aDivider' }])[0].id,
                    pageNodes({ componentName: 'Page', children: [{ componentName: 'OsText' }] })[0].id
                  ],
                  preservedId: pageNodes({
                    componentName: 'Page', children: [{ id: 'saved-node', componentName: 'OsText' }]
                  })[0].id
                }
              }
            `
            : undefined
      }
    ],
    build: { write: false, minify: false, lib: { entry, formats: ['iife'], name: 'UuidCompatibility' } }
  })
  const bundle = Array.isArray(result) ? result[0] : result
  if (!('output' in bundle)) throw new Error('未生成浏览器验证产物')
  const chunk = bundle.output.find(item => item.type === 'chunk')
  if (!chunk) throw new Error('未生成浏览器验证脚本')
  // HTTP 环境仍提供 getRandomValues，但没有 randomUUID；不修改全局 Crypto 对象。
  const browserCrypto = { getRandomValues: webcrypto.getRandomValues.bind(webcrypto) }
  const api = runInNewContext(`${chunk.code}; UuidCompatibility`, { crypto: browserCrypto }) as {
    create(): {
      draft: { id: string | null; titleFieldKey: string; fields: { id: string | null; key: string }[] }
      ids: string[]
      preservedId: string
    }
  }
  const ids: string[] = []
  for (let i = 0; i < 20; i++) {
    const value = api.create()
    expect(value.draft.id).toBeNull()
    expect(value.draft.fields[0].id).toBeNull()
    expect(value.draft.titleFieldKey).toBe(value.draft.fields[0].key)
    expect(value.preservedId).toBe('saved-node')
    ids.push(...value.ids)
  }
  expect(ids).toHaveLength(100)
  expect(new Set(ids).size).toBe(ids.length)
  for (const id of ids) expect(id).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/)
})

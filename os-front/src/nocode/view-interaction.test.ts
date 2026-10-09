import { describe, it, expect } from 'vitest'
import { ViewButton, type ViewConfig } from '@/types/nocode/application-ui'
import { ResourceKind, type ApplicationResource } from '@/types/nocode/application'
import { viewButtonEnabled, viewBusinessActions } from './view-interaction'
import { editSignature } from './edit-signature'
describe('business editing contracts', () => {
  it('keeps old views usable while respecting an explicitly empty button selection', () => {
    expect(viewButtonEnabled(undefined, ViewButton.CREATE)).toBe(true)
    const view = {
      interaction: { buttons: [], actionIds: [], editMode: 'DRAWER', detailMode: 'MODAL' }
    } as unknown as ViewConfig
    expect(viewButtonEnabled(view, ViewButton.CREATE)).toBe(false)
    expect(viewButtonEnabled(view, ViewButton.VIEW)).toBe(false)
  })
  it('never pulls actions from a different object, and respects selected actions', () => {
    const resources = ['company', 'account'].map((objectId, index) => ({
      id: String(index),
      kind: ResourceKind.ACTION,
      code: 'action_' + index,
      name: '动作',
      config: { objectId }
    })) as ApplicationResource[]
    const view = { interaction: { actionIds: ['0', '1'] } } as ViewConfig
    expect(viewBusinessActions(view, resources, 'company').map(a => a.id)).toEqual(['0'])
    expect(
      viewBusinessActions({ interaction: { actionIds: [] } } as unknown as ViewConfig, resources, 'company')
    ).toEqual([])
  })
  it('does not warn for engine-added empty values but detects edits and detail removal', () => {
    expect(editSignature({ values: { name: '公司' } })).toBe(
      editSignature({ values: { unused: '', name: '公司', extra: null } })
    )
    expect(editSignature({ values: { amount: 0 } })).not.toBe(editSignature({ values: {} }))
    expect(editSignature({ details: { items: [{ id: '1', values: { qty: 2 } }] } })).not.toBe(
      editSignature({ details: { items: [] } })
    )
  })
})

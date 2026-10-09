import type { Ref } from 'vue'
import type { FieldPermissionType, SimpleFlowNode } from './consts'
import { computed, inject, ref, toRaw, unref, watch } from 'vue'
import { NODE_DEFAULT_NAME } from './consts'

export function useWatchNode(props: { flowNode: SimpleFlowNode }): Ref<SimpleFlowNode> {
  const node = ref(props.flowNode) as Ref<SimpleFlowNode>
  watch(
    () => props.flowNode,
    value => {
      node.value = value
    }
  )
  return node
}

export function useFormFieldsPermission(defaultPermission: FieldPermissionType) {
  const formFields = inject<Ref<string[]>>('formFields', ref([]))
  const fieldsPermissionConfig = ref<Array<Record<string, any>>>([])
  const formFieldOptions = computed(() => parseFormFields(unref(formFields)))

  function getNodeConfigFormFields(nodeFormFields?: Array<Record<string, string>>) {
    fieldsPermissionConfig.value = nodeFormFields?.length
      ? toRaw(nodeFormFields)
      : formFieldOptions.value.map(item => ({
          field: item.field,
          title: item.title,
          permission: defaultPermission
        }))
  }

  return {
    formType: inject('formType', ref()),
    fieldsPermissionConfig,
    formFieldOptions,
    getNodeConfigFormFields
  }
}

export function useFormFields() {
  const formFields = inject<Ref<string[]>>('formFields', ref([]))
  return parseFormFields(unref(formFields))
}

export function useFormFieldsAndStartUser() {
  return [{ field: 'startUserId', title: '发起人', required: true }, ...useFormFields()]
}

export function useNodeName(nodeType: number) {
  return ref(NODE_DEFAULT_NAME.get(nodeType) || '节点')
}

export function parseFormFields(fields?: string[]) {
  const result: Array<Record<string, any>> = []
  fields?.forEach(field => {
    try {
      const parsed = JSON.parse(field)
      if (Array.isArray(parsed)) {
        result.push(...parsed)
      } else if (parsed && typeof parsed === 'object') {
        result.push(parsed)
      }
    } catch {
      result.push({ field, title: field })
    }
  })
  return result
}

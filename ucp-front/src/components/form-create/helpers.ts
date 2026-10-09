import type { Rule } from '@form-create/ant-design-vue'
import type { Ref } from 'vue'
import { isRef } from 'vue'
import formCreate from '@form-create/ant-design-vue'

export function encodeConf(designerRef: Ref<any> | any) {
  const designer = isRef(designerRef) ? designerRef.value : designerRef
  return formCreate.toJson(designer?.getOption?.() || {})
}

export function decodeConf(conf?: string) {
  if (!conf) return {}
  try {
    return formCreate.parseJson(conf)
  } catch {
    return {}
  }
}

export function encodeFields(designerRef: Ref<any> | any) {
  const designer = isRef(designerRef) ? designerRef.value : designerRef
  const rules = designer?.getRule?.() || []
  return rules.map((item: any) => formCreate.toJson(item))
}

export function decodeFields(fields?: string[] | string) {
  const fieldList = Array.isArray(fields) ? fields : fields ? [fields] : []
  const rules: Rule[] = []
  fieldList.forEach(item => {
    try {
      rules.push(formCreate.parseJson(item))
    } catch {
      // Ignore invalid stored rules so the designer can still open.
    }
  })
  return rules
}

export function setConfAndFields(designerRef: Ref<any> | any, conf?: string, fields?: string[] | string) {
  const designer = isRef(designerRef) ? designerRef.value : designerRef
  designer?.setOption?.(decodeConf(conf))
  designer?.setRule?.(decodeFields(fields))
}

export function setConfAndFields2(detailPreview: Ref<any> | any, conf?: string, fields?: string[], value?: any) {
  const target = isRef(detailPreview) ? detailPreview.value : detailPreview
  target.option = decodeConf(conf)
  target.rule = decodeFields(fields)
  if (value) target.value = value
}

export function parseFormFields(rule: Record<string, any>, fields: Array<Record<string, any>> = [], parentTitle = '') {
  const { type, field, $required, title: tempTitle, children } = rule
  if (field && tempTitle) {
    fields.push({
      field,
      title: parentTitle ? `${parentTitle}.${tempTitle}` : tempTitle,
      type,
      required: Boolean($required)
    })
  }
  if (Array.isArray(children)) {
    children.forEach(child => parseFormFields(child, fields))
  }
  return fields
}

export async function useFormCreateDesigner(_designer: Ref<any>) {
  // The upstream project injects many Vben-specific custom controls here.
  // devops-ui keeps the official form-create designer controls only.
}

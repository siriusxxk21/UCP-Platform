import type { PageParam, PageResult } from '@/types/request'

import type { BpmModelApi } from '@/api/bpm/model'

import request from '@/utils/request'

export namespace BpmProcessDefinitionApi {
  /** 流程定义 */
  export interface ProcessDefinition {
    id: string
    key?: string
    version: number
    name: string
    category: string
    description: string
    deploymentTime: number
    suspensionState: number
    modelType: number
    modelId: string
    formType?: number
    formId?: number
    formName?: string
    formConf?: string
    formCustomCreatePath?: string
    formCustomViewPath?: string
    bpmnXml?: string
    simpleModel?: string
    formFields?: string[]
    icon?: string
    startUsers?: BpmModelApi.UserInfo[]
  }
}

/** 查询流程定义 */
export async function getProcessDefinition(id?: string, key?: string) {
  return request.get<BpmProcessDefinitionApi.ProcessDefinition>('/bpm/process-definition/get', {
    params: { id, key }
  })
}

/** 分页查询流程定义 */
export async function getProcessDefinitionPage(params: PageParam) {
  return request.get<PageResult<BpmProcessDefinitionApi.ProcessDefinition>>('/bpm/process-definition/page', { params })
}

/** 查询流程定义列表 */
export async function getProcessDefinitionList(params: any) {
  return request.get<BpmProcessDefinitionApi.ProcessDefinition[]>('/bpm/process-definition/list', {
    params
  })
}

/** 查询流程定义列表（简单列表） */
export async function getSimpleProcessDefinitionList() {
  return request.get<PageResult<BpmProcessDefinitionApi.ProcessDefinition>>('/bpm/process-definition/simple-list')
}

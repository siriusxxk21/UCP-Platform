import { createTaskWorkEntriesApi } from './task-work-entries'
import { createTaskEntryFilesApi } from './task-entry-files'
import { createTaskEfficiencyApi } from './task-efficiency'
import { createTaskManagementApi } from './task-management'
import { createTaskWorkTimeApi } from './task-work-time'
import type { NocodeHttpClient } from './object'
import type { Page } from '@/types/nocode/data-center'
import type { SaveRecord } from '@/types/nocode/runtime'
import type * as T from '@/types/nocode/task-center'
import {
  taskCreateToWire,
  taskDateToWire,
  taskDetailFromWire,
  taskDraftFromWire,
  taskNodeFromWire,
  taskNodeToWire,
  taskRowFromWire,
  taskSchedulePreviewFromWire
} from '@/nocode/task-center-wire'

/** 任务身份、归属与业务字段权限由服务端校验，客户端不传入操作者身份。 */
export function createTaskCenterApi(client: NocodeHttpClient) {
  const root = '/nocode/tasks',
    templates = '/nocode/task-templates'
  const personalNode = (node: {
    task: T.TaskRow
    matchingChildCount: number
    contextOnly?: boolean
    detailVisible?: boolean
    myPendingCount?: number
    myCompletedCount?: number
    completedChildCount?: number
    structure?: T.TaskStructureNode
    anchorTaskId?: string
  }): T.TaskRow => ({
    ...taskRowFromWire(node.task),
    // 完整结构来自服务端安全投影；不以摘要覆盖任务内容、清单或操作能力。
    ...(node.structure
      ? {
          id: node.structure.id,
          rootId: node.structure.rootId,
          parentId: node.structure.parentId,
          title: node.structure.title,
          status: node.structure.status,
          assigneeName: node.structure.assigneeName,
          expectedStart: node.structure.expectedStart,
          expectedEnd: node.structure.expectedEnd,
          scheduleSummary: node.structure.scheduleSummary,
          predecessorIds: node.structure.predecessorIds
        }
      : {}),
    anchorTaskId: node.anchorTaskId,
    matchingChildCount: node.matchingChildCount,
    contextOnly: node.contextOnly,
    detailVisible: node.detailVisible,
    myPendingCount: node.myPendingCount,
    myCompletedCount: node.myCompletedCount,
    completedChildCount: node.completedChildCount
  })
  return {
    ...createTaskWorkEntriesApi(client),
    ...createTaskEntryFilesApi(client),
    ...createTaskEfficiencyApi(client),
    ...createTaskManagementApi(client),
    ...createTaskWorkTimeApi(client),
    page: (query: T.TaskQuery) =>
      client
        .post<Page<T.TaskRow>>(`${root}/page`, query)
        .then(page => ({ ...page, list: page.list.map(taskRowFromWire) })),
    personalTreePage: (query: T.TaskQuery) =>
      client.post<Page<Parameters<typeof personalNode>[0]>>(`${root}/personal-tree-page`, query).then(page => ({
        ...page,
        list: page.list.map(personalNode)
      })),
    personalTreeChildren: (query: T.TaskQuery, parentId: string) =>
      client
        .post<Array<Parameters<typeof personalNode>[0]>>(`${root}/personal-tree-children`, {
          query,
          parentId
        })
        .then(nodes => nodes.map(personalNode)),
    pageTasks: (context: T.TaskPageContext, query: T.TaskQuery) =>
      client
        .post<Page<T.TaskRow>>(`${root}/page-tasks`, { ...context, query })
        .then(page => ({ ...page, list: page.list.map(taskRowFromWire) })),
    detail: (id: string) => client.post<T.TaskDetail>(`${root}/detail`, { id }).then(taskDetailFromWire),
    readiness: (id: string) => client.post<T.TaskReadiness>(`${root}/readiness`, { id }),
    create: (body: T.TaskCreate) =>
      client.post<T.TaskDetail>(`${root}/create`, taskCreateToWire(body)).then(taskDetailFromWire),
    // 员工拆分只传当前父任务与新节点，业务授权和归属由服务端继承。
    split: (body: Pick<T.TaskCreate, 'task' | 'requestKey'> & { parentId: string }) =>
      client.post<T.TaskDetail>(`${root}/split`, taskCreateToWire(body)).then(taskDetailFromWire),
    deleteSubtask: (body: { id: string; expectedRevision: number; requestKey: string }) =>
      client.post<boolean>(`${root}/delete-subtask`, body),
    drafts: () => client.get<T.TaskDraftSummary[]>(`${root}/drafts`),
    draftGet: (id: string) => client.post<T.TaskDraft>(`${root}/draft-get`, { id }).then(taskDraftFromWire),
    draftSave: (body: { id: string; expectedRevision: number; content: T.TaskCreate }) =>
      client
        .post<T.TaskDraft>(`${root}/draft-save`, { ...body, content: taskCreateToWire(body.content) })
        .then(taskDraftFromWire),
    draftDelete: (id: string, expectedRevision: number) =>
      client.post<boolean>(`${root}/draft-delete`, { id, expectedRevision }),
    draftPublish: (body: { id: string; expectedRevision: number; requestKey: string }) =>
      client.post<T.TaskDetail>(`${root}/draft-publish`, body).then(taskDetailFromWire),
    claim: (body: { id: string; expectedRevision: number; requestKey: string }) =>
      client.post<T.TaskDetail>(`${root}/claim`, body).then(taskDetailFromWire),
    claimableGroups: (query: {
      search?: string
      urgency?: T.TaskUrgency
      priority?: T.TaskPriority
      pageNo: number
      pageSize: number
    }) => client.post<Page<T.TaskClaimableGroup>>(`${root}/claimable-groups`, query),
    claimableChildren: (rootId: string) => client.post<T.TaskClaimableItem[]>(`${root}/claimable-children`, { rootId }),
    claimPreview: (rootId: string, includeOpen?: boolean) =>
      client.post<T.TaskClaimPreview>(`${root}/claim-preview`, {
        rootId,
        ...(includeOpen === undefined ? {} : { includeOpen })
      }),
    claimGroup: (body: T.TaskClaimGroupCommand) =>
      client.post<T.TaskDetail>(`${root}/claim-group`, body).then(taskDetailFromWire),
    assign: (body: {
      id: string
      expectedRevision: number
      assignmentMode: T.TaskAssignmentMode
      assigneeId: T.TaskUserId | null
      candidateUserIds: T.TaskUserId[]
      acceptance?: { acceptorId: T.TaskUserId | null }
      requestKey: string
      note?: string
      transfer?: boolean
    }) => client.post<T.TaskDetail>(`${root}/assign`, body).then(taskDetailFromWire),
    transition: (body: T.TaskTransitionCommand) =>
      client.post<T.TaskDetail>(`${root}/transition`, body).then(taskDetailFromWire),
    transitionRecovery: (body: T.TaskTransitionCommand) =>
      client.post<T.TaskTransitionRecovery>(`${root}/transition-recovery`, body).then(result => ({
        ...result,
        applied: result.applied ? taskDetailFromWire(result.applied) : null
      })),
    plan: (body: {
      ids: string[]
      period: T.TaskPeriod
      date: string
      include: boolean
      target?: 'SELF' | 'ASSIGNEE'
    }) => client.post<boolean>(`${root}/plan`, body),
    planContext: (body: { ids: string[]; target: 'SELF' | 'ASSIGNEE' }) =>
      client.post<T.TaskPlanContext>(`${root}/plan-context`, body),
    schedule: (body: T.TaskScheduleCommand) => client.post<T.TaskScheduleResult>(`${root}/schedule`, body),
    checklistContext: (body: { ids: string[]; target: 'SELF' | 'ASSIGNEE' }) =>
      client.post<T.TaskChecklistContext>(`${root}/checklist-context`, body),
    checklist: (body: T.TaskChecklistCommand) => client.post<T.TaskScheduleResult>(`${root}/checklist`, body),
    recordLinkCandidates: (context: T.TaskPageContext, query: T.TaskQuery) =>
      client.post<Page<T.TaskRow>>(`${root}/record-link-candidates`, { ...context, query }),
    recordLink: (body: {
      context: { applicationId: string; pageId: string; nodeId: string; recordId: string }
      taskId: string
      expectedRevision: number
      include: boolean
      requestKey: string
    }) => client.post<T.TaskDetail>(`${root}/record-link`, body),
    comment: (body: {
      taskId: string
      parentId: string | null
      content: string
      mentionedUserIds: T.TaskUserId[]
      requestKey: string
    }) => client.post<T.TaskComment>(`${root}/comment`, body),
    members: (taskId?: string) => client.get<T.TaskMember[]>(`${root}/members`, { params: { taskId } }),
    entryOptions: () => client.get<Array<{ value: string; label: string }>>(`${root}/entry-options`),
    form: (id: string, options?: { quiet?: boolean }) =>
      client.post<T.TaskFormContext>(`${root}/form`, { id }, options),
    material: (taskId: string, eventId: string) => client.post<T.TaskMaterial>(`${root}/material`, { taskId, eventId }),
    formPreview: (binding: T.TaskBinding) => client.post<T.TaskFormContext>(`${root}/form-preview`, binding),
    handlingTask: (id: string) => client.post<string | null>(`${root}/form-runtime/handling-task`, { id }),
    formReceipt: (taskId: string, requestKey: string) =>
      client.post<import('@/types/nocode/handling').HandlingResult | null>(`${root}/form-runtime/receipt`, {
        taskId,
        requestKey
      }),
    createReceipt: (requestKey: string) =>
      client.post<{ taskId: string; handling: import('@/types/nocode/handling').HandlingResult } | null>(
        `${root}/form-runtime/create-receipt`,
        { requestKey }
      ),
    formSelection: (taskId: string, query: import('@/types/nocode/selection').SelectionQuery) =>
      client.post<import('@/types/nocode/selection').SelectionResult>(`${root}/form-runtime/selection`, {
        taskId,
        query
      }),
    formFill: (taskId: string, query: import('@/types/nocode/application-ui').FormFillQuery) =>
      client.post<Record<string, unknown>>(`${root}/form-runtime/form-fill`, { taskId, query }),
    formFieldRules: (taskId: string, query: import('@/types/nocode/field-rules').FieldRuleEvaluateQuery) =>
      client.post<{ results: import('@/types/nocode/field-rules').FieldRuleResult[] }>(
        `${root}/form-runtime/field-rules`,
        { taskId, query }
      ),
    relatedFieldRules: (
      taskId: string,
      context: import('@/types/nocode/runtime').RelatedFormQuery,
      query: import('@/types/nocode/field-rules').FieldRuleEvaluateQuery
    ) =>
      client.post<{ results: import('@/types/nocode/field-rules').FieldRuleResult[] }>(
        `${root}/form-runtime/related-field-rules`,
        { taskId, query: { context, query } }
      ),
    relatedForm: (taskId: string, query: import('@/types/nocode/runtime').RelatedFormQuery) =>
      client.post<import('@/types/nocode/runtime').RelatedFormResult>(`${root}/form-runtime/related-form`, {
        taskId,
        query
      }),
    relatedSelection: (
      taskId: string,
      context: import('@/types/nocode/runtime').RelatedFormQuery,
      query: import('@/types/nocode/selection').SelectionQuery
    ) =>
      client.post<import('@/types/nocode/selection').SelectionResult>(`${root}/form-runtime/related-selection`, {
        taskId,
        query: { context, query }
      }),
    relatedFill: (
      taskId: string,
      context: import('@/types/nocode/runtime').RelatedFormQuery,
      query: import('@/types/nocode/application-ui').FormFillQuery
    ) =>
      client.post<Record<string, unknown>>(`${root}/form-runtime/related-fill`, { taskId, query: { context, query } }),
    saveBusiness: (body: { taskId: string; expectedRevision: number; record: SaveRecord }) =>
      client.post<T.TaskFormContext>(`${root}/business`, body),
    schedulePreview: (body: { nodes: T.TaskNodeInput[]; plannedStart: string | null }) =>
      client
        .post<T.TaskSchedulePreview>(`${root}/schedule-preview`, {
          nodes: body.nodes.map(taskNodeToWire),
          plannedStart: taskDateToWire(body.plannedStart)
        })
        .then(taskSchedulePreviewFromWire),
    adjustPreview: (body: T.TaskAdjust) =>
      client
        .post<T.TaskAdjustmentPreview>(`${root}/adjust-preview`, {
          ...body,
          nodes: body.nodes.map(taskNodeToWire),
          ...(body.plannedStart !== undefined ? { plannedStart: taskDateToWire(body.plannedStart) } : {})
        })
        .then(preview => ({
          ...preview,
          ...(preview.schedule ? { schedule: taskSchedulePreviewFromWire(preview.schedule) } : {})
        })),
    adjust: (body: T.TaskAdjust) =>
      client
        .post<T.TaskDetail>(`${root}/adjust`, {
          ...body,
          nodes: body.nodes.map(taskNodeToWire),
          ...(body.plannedStart !== undefined ? { plannedStart: taskDateToWire(body.plannedStart) } : {})
        })
        .then(taskDetailFromWire),
    templates: () =>
      client.get<T.TaskTemplate[]>(`${templates}/list`).then(items =>
        items.map(item => ({
          ...item,
          task: item.task ? taskNodeFromWire(item.task) : item.task,
          nodes: item.nodes.map(taskNodeFromWire)
        }))
      ),
    templateInstances: (query: T.TaskTemplateInstanceQuery) =>
      client.post<Page<T.TaskTemplateInstance>>(`${templates}/instances`, query).then(page => ({
        ...page,
        list: page.list.map(instance => ({
          ...instance,
          root: instance.root ? taskRowFromWire(instance.root) : null,
          nodes: instance.nodes.map(taskRowFromWire)
        }))
      })),
    saveTemplate: (body: T.SaveTaskTemplate) =>
      client
        .post<T.TaskTemplate>(`${templates}/save`, {
          ...body,
          task: body.task ? taskNodeToWire(body.task) : body.task,
          nodes: body.nodes.map(taskNodeToWire)
        })
        .then(item => ({
          ...item,
          task: item.task ? taskNodeFromWire(item.task) : item.task,
          nodes: item.nodes.map(taskNodeFromWire)
        })),
    templateVersions: (id: string) =>
      client.get<T.TaskTemplateVersionSummary[]>(`${templates}/versions`, { params: { id } }),
    setPrimaryTemplateVersion: (id: string, version: number, expectedRevision: number) =>
      client.post<T.TaskTemplate>(`${templates}/primary`, { id, version, expectedRevision }).then(item => ({
        ...item,
        task: item.task ? taskNodeFromWire(item.task) : item.task,
        nodes: item.nodes.map(taskNodeFromWire)
      })),
    publishTemplate: (id: string, expectedRevision: number, setAsPrimary?: boolean) =>
      client
        .post<T.TaskTemplateVersion>(`${templates}/publish`, {
          id,
          expectedRevision,
          ...(setAsPrimary === undefined ? {} : { setAsPrimary })
        })
        .then(item => ({
          ...item,
          task: item.task ? taskNodeFromWire(item.task) : item.task,
          nodes: item.nodes.map(taskNodeFromWire)
        })),
    templateVersion: (id: string, version?: number) =>
      client.get<T.TaskTemplateVersion>(`${templates}/version`, { params: { id, version } }).then(item => ({
        ...item,
        task: item.task ? taskNodeFromWire(item.task) : item.task,
        nodes: item.nodes.map(taskNodeFromWire)
      }))
  }
}
export type TaskCenterApi = ReturnType<typeof createTaskCenterApi>

/** 数据中心稳定业务编码。与后端同名领域枚举对应，类型从编码定义推导。 */
export const FieldType = {
  TEXT: 'TEXT',
  TEXTAREA: 'TEXTAREA',
  INTEGER: 'INTEGER',
  DECIMAL: 'DECIMAL',
  BOOLEAN: 'BOOLEAN',
  DATE: 'DATE',
  DATETIME: 'DATETIME',
  RICH_TEXT: 'RICH_TEXT',
  URL: 'URL',
  MONEY: 'MONEY',
  PERCENT: 'PERCENT',
  TIME: 'TIME',
  SELECT: 'SELECT',
  MULTI_SELECT: 'MULTI_SELECT',
  ORGANIZATION: 'ORGANIZATION',
  USER: 'USER',
  DEPARTMENT: 'DEPARTMENT',
  POST: 'POST',
  USER_GROUP: 'USER_GROUP',
  IMAGE: 'IMAGE',
  ATTACHMENT: 'ATTACHMENT',
  REGION: 'REGION',
  CASCADE: 'CASCADE',
  AUTO_NUMBER: 'AUTO_NUMBER',
  FORMULA: 'FORMULA',
  SUMMARY: 'SUMMARY',
  REFERENCE: 'REFERENCE',
  UUID: 'UUID'
} as const
export type FieldType = (typeof FieldType)[keyof typeof FieldType]

export const ObjectStatus = {
  DRAFT: 'DRAFT',
  ACTIVE: 'ACTIVE',
  DISABLED: 'DISABLED',
  DELETED: 'DELETED'
} as const
export type ObjectStatus = (typeof ObjectStatus)[keyof typeof ObjectStatus]

export const VersionState = {
  DRAFT: 'DRAFT',
  PUBLISHED: 'PUBLISHED'
} as const
export type VersionState = (typeof VersionState)[keyof typeof VersionState]

export const MemberState = {
  ACTIVE: 'ACTIVE',
  INACTIVE: 'INACTIVE'
} as const
export type MemberState = (typeof MemberState)[keyof typeof MemberState]

export const ObjectSource = {
  GENERATED: 'GENERATED',
  ADOPTED: 'ADOPTED'
} as const
export type ObjectSource = (typeof ObjectSource)[keyof typeof ObjectSource]

export const RelationType = {
  REFERENCE: 'REFERENCE',
  MASTER_DETAIL: 'MASTER_DETAIL',
  ONE_TO_ONE: 'ONE_TO_ONE',
  MANY_TO_MANY: 'MANY_TO_MANY'
} as const
export type RelationType = (typeof RelationType)[keyof typeof RelationType]

export const DeletePolicy = {
  RESTRICT: 'RESTRICT',
  CASCADE: 'CASCADE',
  SET_NULL: 'SET_NULL'
} as const
export type DeletePolicy = (typeof DeletePolicy)[keyof typeof DeletePolicy]

export const DataClassification = {
  NORMAL: 'NORMAL',
  INTERNAL: 'INTERNAL',
  SENSITIVE: 'SENSITIVE',
  SECRET: 'SECRET'
} as const
export type DataClassification = (typeof DataClassification)[keyof typeof DataClassification]

export const DisplayResolver = {
  NONE: 'NONE',
  LOCAL_OPTIONS: 'LOCAL_OPTIONS',
  RECORD_TITLE: 'RECORD_TITLE',
  USER: 'USER',
  DEPARTMENT: 'DEPARTMENT'
} as const
export type DisplayResolver = (typeof DisplayResolver)[keyof typeof DisplayResolver]

export const PublishState = {
  PENDING: 'PENDING',
  BLOCKED: 'BLOCKED',
  SUCCEEDED: 'SUCCEEDED',
  FAILED: 'FAILED'
} as const
export type PublishState = (typeof PublishState)[keyof typeof PublishState]

export const TableRole = {
  UNMANAGED: 'UNMANAGED',
  MAIN: 'MAIN',
  DETAIL: 'DETAIL',
  RELATION: 'RELATION'
} as const
export type TableRole = (typeof TableRole)[keyof typeof TableRole]

export const TableManagement = {
  UNMANAGED: 'UNMANAGED',
  PENDING: 'PENDING',
  GENERATED: 'GENERATED',
  ADOPTED: 'ADOPTED',
  DISABLED: 'DISABLED'
} as const
export type TableManagement = (typeof TableManagement)[keyof typeof TableManagement]

export const StructureState = {
  UNMANAGED: 'UNMANAGED',
  PENDING: 'PENDING',
  MATCHED: 'MATCHED',
  DRIFTED: 'DRIFTED'
} as const
export type StructureState = (typeof StructureState)[keyof typeof StructureState]

/** 每张表独立决定结构变更权限。 */
export const StructureMode = { RETAIN: 'RETAIN', MANAGED: 'MANAGED' } as const
export type StructureMode = (typeof StructureMode)[keyof typeof StructureMode]

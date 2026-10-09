import { inject, type InjectionKey, type Ref, type ComputedRef } from 'vue'
import type { ReportFilter } from '@/types/nocode/report'
export interface ReportDashboard {
  definitions: ComputedRef<ReportFilter[]>
  values: Ref<Record<string, unknown>>
}
export const reportDashboardKey: InjectionKey<ReportDashboard> = Symbol.for('nocode-report-dashboard')
export const useReportDashboard = () => inject(reportDashboardKey, undefined)

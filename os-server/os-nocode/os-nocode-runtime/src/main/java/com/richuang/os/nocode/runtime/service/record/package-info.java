/**
 * 公共记录读写和紧密协作的校验、关联、明细、计算、收据及事务组件。外部业务操作从 RecordService 进入；视图与报表通过 RecordQueryAccess
 * 复用查询准备和结果投影。同包内部方法保持包可见，不拆成全局 util 或按 Reader/Writer 分包。
 */
package com.richuang.os.nocode.runtime.service.record;

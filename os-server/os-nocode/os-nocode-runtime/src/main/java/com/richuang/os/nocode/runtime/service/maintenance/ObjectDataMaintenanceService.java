package com.richuang.os.nocode.runtime.service.maintenance;

import com.richuang.os.framework.common.pojo.PageResult;
import com.richuang.os.nocode.api.*;

import java.util.function.Supplier;

/** 管理员维护已发布对象的共享业务记录；所有方法都重新验证管理权限。 */
public interface ObjectDataMaintenanceService {

    /**
     * 只读维护入口
     *
     * <p>与写入口一样重新验证数据对象管理权限并建立维护上下文，但不获取结构锁； 供业务文件浏览等跨对象的只读读取复用同一授权边界。
     */
    <T> T read(long actor, Supplier<T> work);

    ObjectDataMaintenance.Model model(String objectId, long actor);

    PageResult<ApplicationRecords.Row> page(ObjectDataMaintenance.Query query, long actor);

    ApplicationRecords.Aggregate get(String objectId, String id, long actor);

    ApplicationRecords.Aggregate save(ObjectDataMaintenance.Save request, long actor);

    SelectionFields.Result selection(ObjectDataMaintenance.Selection request, long actor);

    ObjectDataMaintenance.DeletePreview previewDelete(
            ObjectDataMaintenance.Delete request, long actor);

    void delete(ObjectDataMaintenance.Delete request, long actor);

    ObjectDataMaintenance.ClearColumnPreview previewClearColumn(
            ObjectDataMaintenance.ClearColumn request, long actor);

    ObjectDataMaintenance.ClearColumnResult clearColumn(
            ObjectDataMaintenance.ClearColumn request, long actor);
}

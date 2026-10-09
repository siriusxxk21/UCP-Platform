package com.lingan.ucp.nocode.runtime.dal.mapper;

import com.lingan.ucp.nocode.api.TaskCenter;
import com.lingan.ucp.nocode.api.TaskClaims;
import com.lingan.ucp.nocode.api.TaskManagement;
import com.lingan.ucp.nocode.api.work.PublishedResourceRef;
import com.lingan.ucp.nocode.runtime.dal.dataobject.*;
import com.lingan.ucp.nocode.runtime.dal.query.TaskEntryCandidate;
import com.lingan.ucp.nocode.runtime.dal.query.TaskQueryScope;

import org.apache.ibatis.annotations.*;

import java.time.LocalDate;
import java.util.List;

/** SQL统一落在XML；所有修改由服务层根实例锁和修订控制。 */
@Mapper
public interface TaskCenterMapper {
    void requestLock(@Param("key") String key);

    TaskInstanceDO get(@Param("id") String id, @Param("lock") boolean lock);

    /** 取得根锁后刷新任务，不能复用锁前解析 rootId 时缓存的修订、执行人或状态。 */
    TaskInstanceDO planningTask(@Param("id") String id);

    List<String> claimableRoots(
            @Param("q") TaskClaims.Query query,
            @Param("actor") long actor,
            @Param("offset") int offset,
            @Param("limit") int limit);

    long claimableRootCount(@Param("q") TaskClaims.Query query, @Param("actor") long actor);

    List<TaskInstanceDO> instance(@Param("root") String root);

    List<TaskInstanceDO> templateInstances(
            @Param("q") TaskCenter.TemplateInstances query,
            @Param("actor") long actor,
            @Param("admin") boolean admin,
            @Param("offset") int offset,
            @Param("limit") int limit);

    long templateInstanceCount(
            @Param("q") TaskCenter.TemplateInstances query,
            @Param("actor") long actor,
            @Param("admin") boolean admin);

    TaskInstanceDO created(@Param("actor") String actor, @Param("key") String key);

    List<TaskEntryCandidate> entryOptions(
            @Param("actor") long actor, @Param("admin") boolean admin);

    List<TaskInstanceDO> page(
            @Param("q") TaskCenter.Query query,
            @Param("actor") long actor,
            @Param("admin") boolean admin,
            @Param("offset") int offset,
            @Param("limit") int limit,
            @Param("scope") TaskQueryScope scope);

    long count(
            @Param("q") TaskCenter.Query query,
            @Param("actor") long actor,
            @Param("admin") boolean admin,
            @Param("scope") TaskQueryScope scope);

    /** 管理筛选与统计共用授权节点集合，SQL 中先聚合、后分页。 */
    List<TaskInstanceDO> managementPage(
            @Param("m") TaskManagement.Query management,
            @Param("q") TaskCenter.Query query,
            @Param("actor") long actor,
            @Param("admin") boolean admin,
            @Param("offset") int offset,
            @Param("limit") int limit,
            @Param("scope") TaskQueryScope scope);

    long managementCount(
            @Param("m") TaskManagement.Query management,
            @Param("q") TaskCenter.Query query,
            @Param("actor") long actor,
            @Param("admin") boolean admin,
            @Param("scope") TaskQueryScope scope);

    List<TaskManagement.Employee> managementEmployees(
            @Param("employees") TaskManagement.Employees employees,
            @Param("q") TaskCenter.Query query,
            @Param("actor") long actor,
            @Param("admin") boolean admin,
            @Param("offset") int offset,
            @Param("limit") int limit,
            @Param("scope") TaskQueryScope scope);

    long managementEmployeeCount(
            @Param("employees") TaskManagement.Employees employees,
            @Param("q") TaskCenter.Query query,
            @Param("actor") long actor,
            @Param("admin") boolean admin,
            @Param("scope") TaskQueryScope scope);

    boolean matches(
            @Param("id") String id,
            @Param("q") TaskCenter.Query query,
            @Param("actor") long actor,
            @Param("admin") boolean admin,
            @Param("scope") TaskQueryScope scope);

    /** 完整匹配集合先按最近匹配祖先归并，再对入口分页。 */
    List<TaskInstanceDO> personalTreePage(
            @Param("q") TaskCenter.Query query,
            @Param("actor") long actor,
            @Param("admin") boolean admin,
            @Param("offset") int offset,
            @Param("limit") int limit,
            @Param("scope") TaskQueryScope scope);

    long personalTreeCount(
            @Param("q") TaskCenter.Query query,
            @Param("actor") long actor,
            @Param("admin") boolean admin,
            @Param("scope") TaskQueryScope scope);

    /** 展开入口必须仍属于同一筛选结果的有权上下文，不能只按传入根标识放行。 */
    boolean personalTreeContains(
            @Param("q") TaskCenter.Query query,
            @Param("id") String id,
            @Param("actor") long actor,
            @Param("admin") boolean admin,
            @Param("scope") TaskQueryScope scope);

    List<TaskInstanceDO> personalTreeChildren(
            @Param("q") TaskCenter.Query query,
            @Param("parentId") String parentId,
            @Param("actor") long actor,
            @Param("admin") boolean admin,
            @Param("scope") TaskQueryScope scope);

    /** 审批生效仅允许使用任务自身封存且仍由原申请人负责的业务版本。 */
    boolean ownsHandlingRequest(
            @Param("request") String request,
            @Param("resource") PublishedResourceRef resource,
            @Param("object") String object,
            @Param("actor") long actor);

    /** 原申请人从申请列表重提时，定位仍关联该申请且由其负责的任务。 */
    String handlingTaskId(@Param("request") String request, @Param("actor") long actor);

    List<TaskRecordLinkDO> links(@Param("task") String task);

    TaskRecordLinkDO recordLink(
            @Param("task") String task, @Param("record") TaskCenter.RecordRef record);

    void saveRecordLink(@Param("row") TaskRecordLinkDO row, @Param("actor") String actor);

    void removeRecordLink(@Param("id") String id, @Param("actor") String actor);

    void insertTask(@Param("row") TaskInstanceDO row, @Param("actor") String actor);

    int updateTask(@Param("row") TaskInstanceDO row, @Param("actor") String actor);

    /** 根锁内批量核对整棵待删除分支，已有业务痕迹或封存材料时保留任务身份。 */
    boolean hasRemovalEvidence(@Param("ids") List<String> ids);

    /** 员工删除不可绕过历史办理事实，即使原关联、计时事实已冲销或逻辑删除也应保留任务。 */
    boolean hasSubtaskRemovalEvidence(@Param("task") String task);

    /** 办理项的持久化来源也需要保护，不仅校验前端可见的节点配置。 */
    boolean hasSubtaskDataReferences(@Param("task") String task);

    void removeTask(@Param("id") String id, @Param("actor") String actor);

    /** 改派后原执行人的个人计划不跟随任务转移。 */
    void removeTaskPlans(@Param("task") String task, @Param("actor") String actor);

    /** 删除节点使个人计划退出当前清单，保留其历史原因与审计。 */
    void archiveDeletedSubtaskPlans(@Param("task") String task, @Param("actor") String actor);

    List<TaskPlanDO> plans(@Param("user") long user, @Param("ids") List<String> ids);

    /** 当前负责人有效清单（含同负责人祖先继承）；旧 SCHEDULE 仍仅返回当前节点原记录。 */
    List<TaskPlanDO> effectivePlans(@Param("user") long user, @Param("ids") List<String> ids);

    List<TaskPlanDO> planHistory(@Param("task") String task);

    int archivePlan(
            @Param("id") String id, @Param("reason") String reason, @Param("actor") String actor);

    int advanceSchedule(
            @Param("task") String task,
            @Param("version") int version,
            @Param("actor") String actor);

    void savePlan(@Param("row") TaskPlanDO row, @Param("actor") String actor);

    void removePlan(
            @Param("task") String task,
            @Param("user") long user,
            @Param("period") String period,
            @Param("date") LocalDate date,
            @Param("actor") String actor);

    List<TaskCommentDO> comments(@Param("root") String root);

    TaskCommentDO comment(@Param("id") String id);

    TaskCommentDO commented(@Param("actor") String actor, @Param("key") String key);

    void insertComment(@Param("row") TaskCommentDO row, @Param("actor") String actor);

    List<TaskHistoryDO> events(@Param("root") String root);

    TaskHistoryDO requested(@Param("actor") String actor, @Param("key") String key);

    /** 批量清单回执逐任务保存，事件正文不携带其他总任务的身份。 */
    TaskHistoryDO checklistReceipt(
            @Param("task") String task, @Param("actor") String actor, @Param("hash") String hash);

    void appendEvent(@Param("row") TaskHistoryDO row, @Param("actor") String actor);

    List<TaskTemplateDO> templates(@Param("actor") String actor, @Param("admin") boolean admin);

    TaskTemplateDO template(@Param("id") String id, @Param("lock") boolean lock);

    void insertTemplate(@Param("row") TaskTemplateDO row, @Param("actor") String actor);

    int updateTemplate(@Param("row") TaskTemplateDO row, @Param("actor") String actor);

    /** 切换主版本只更新指针与并发修订，不触碰正在编辑的草稿。 */
    int updatePrimaryVersion(
            @Param("id") String id,
            @Param("version") int version,
            @Param("expectedRevision") int expectedRevision,
            @Param("actor") String actor);

    List<TaskTemplateVersionDO> versions(@Param("id") String id);

    TaskTemplateVersionDO version(@Param("id") String id, @Param("version") int version);

    void insertVersion(@Param("row") TaskTemplateVersionDO row, @Param("actor") String actor);
}

package com.richuang.os.nocode.tools;

import static com.richuang.os.nocode.tools.NocodeIntegrationSupport.*;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.richuang.os.nocode.api.*;
import com.richuang.os.nocode.application.service.application.ApplicationFollowService;
import com.richuang.os.nocode.application.service.application.ApplicationService;
import com.richuang.os.nocode.application.service.authorization.ApplicationAuthorizationService;
import com.richuang.os.nocode.application.service.sharing.ObjectSharingService;

import org.springframework.test.util.ReflectionTestUtils;

import java.util.*;

/**
 * 应用自动跟随集成用例的夹具：自有前缀的对象、应用与在途审批行；只清理本夹具创建的数据，在途审批用的是虚构行。
 *
 * <p>对象固定有标题字段 name、整数字段 amount、文本字段 memo；应用按首次引用时系统写入的默认上限运行（不走显式全量授权的夹具）。
 */
final class FollowFixture {
    final NocodeIntegrationSupport support = new NocodeIntegrationSupport();
    final ApplicationService apps;
    final ApplicationFollowService follows;
    final ObjectSharingService sharing;
    final ApplicationAuthorizationService authorization;
    final DataObjectApi objects;
    final long owner = 10001L;
    private int serial;

    FollowFixture() {
        support.name();
        apps = servicesContext.getBean(ApplicationService.class);
        follows = servicesContext.getBean(ApplicationFollowService.class);
        sharing = servicesContext.getBean(ObjectSharingService.class);
        authorization = servicesContext.getBean(ApplicationAuthorizationService.class);
        objects = servicesContext.getBean(DataObjectApi.class);
        configure(true, "strict");
    }

    /** 切换总开关与在途策略（生产上是配置项，测试里直接改 Bean 的字段）。 */
    void configure(boolean enabled, String policy) {
        ReflectionTestUtils.setField(follows, "enabled", enabled);
        ReflectionTestUtils.setField(follows, "inFlightPolicy", policy);
    }

    String prefix() {
        return support.prefix;
    }

    // ── 对象 ──

    static FieldDefinition field(String code, String name, String type, boolean required) {
        return new FieldDefinition(
                code,
                null,
                code,
                name,
                type,
                "TEXT".equals(type) ? 100 : null,
                null,
                null,
                required,
                false,
                10);
    }

    /** 新建并发布对象，返回对象 ID。 */
    String object(String suffix, String name) {
        String code = support.prefix + suffix + serial++;
        List<FieldDefinition> fields = new ArrayList<>();
        fields.add(
                new FieldDefinition(
                        "name", null, "name", "名称", "TEXT", 100, null, null, false, false, 0));
        fields.add(field("amount", "金额", "INTEGER", false));
        fields.add(field("memo", "备注", "TEXT", false));
        DataCenter.Design design =
                designs.save(
                        new DataCenter.SaveDesign(
                                new SaveObjectDraft(
                                        null,
                                        null,
                                        code,
                                        name,
                                        null,
                                        "biz_" + code,
                                        "name",
                                        fields,
                                        List.of()),
                                DataCenter.Settings.defaults(),
                                Map.of(),
                                List.of(),
                                List.of(),
                                List.of()),
                        owner);
        publish(plan(design.draft().id()), "初始发布", owner);
        return design.draft().id();
    }

    DataCenter.Definition latest(String objectId) {
        return objects.getVersion(objectId, null).definition();
    }

    ApplicationCenter.ObjectReference reference(String objectId) {
        DataObjectApi.PublishedObject version = objects.getVersion(objectId, null);
        return new ApplicationCenter.ObjectReference(
                objectId, version.versionNo(), version.checksum());
    }

    String fieldId(String objectId, String code) {
        return latest(objectId).fields().stream()
                .filter(f -> f.code().equals(code))
                .findFirst()
                .orElseThrow()
                .id();
    }

    /** 打开可编辑草稿，按 change 调整字段后保存（不发布）。removals 是要停用的字段 ID。 */
    DataCenter.Design edit(
            String objectId,
            java.util.function.UnaryOperator<List<FieldDefinition>> change,
            List<String> removals) {
        DataCenter.Design current = designs.get(objectId);
        DataCenter.Design editable =
                designs.editPublished(
                        new DataCenter.Revision(objectId, current.draft().lockVersion(), null),
                        owner);
        ObjectDraft draft = editable.draft();
        List<FieldDefinition> fields = change.apply(new ArrayList<>(draft.fields()));
        Map<String, DataCenter.FieldOptions> options = new HashMap<>(editable.fieldOptions());
        removals.forEach(options::remove);
        return designs.save(
                new DataCenter.SaveDesign(
                        new SaveObjectDraft(
                                draft.id(),
                                draft.lockVersion(),
                                draft.objectCode(),
                                draft.objectName(),
                                draft.description(),
                                draft.tableName(),
                                draft.titleFieldId(),
                                fields,
                                removals),
                        editable.settings(),
                        options,
                        editable.relations(),
                        editable.indexes(),
                        editable.details(),
                        editable.mainBinding()),
                owner);
    }

    DataCenter.Design addField(String objectId, String code, String name) {
        return addField(objectId, field(code, name, "TEXT", false));
    }

    DataCenter.Design addField(String objectId, FieldDefinition field) {
        return edit(
                objectId,
                fields -> {
                    fields.add(field);
                    return fields;
                },
                List.of());
    }

    DataCenter.Design renameField(String objectId, String code, String name) {
        return edit(
                objectId,
                fields ->
                        fields.stream()
                                .map(
                                        f ->
                                                f.code().equals(code)
                                                        ? new FieldDefinition(
                                                                f.key(),
                                                                f.id(),
                                                                f.code(),
                                                                name,
                                                                f.type(),
                                                                f.length(),
                                                                f.precision(),
                                                                f.scale(),
                                                                f.required(),
                                                                f.unique(),
                                                                f.sort())
                                                        : f)
                                .toList(),
                List.of());
    }

    DataCenter.Design removeField(String objectId, String code) {
        String id = fieldId(objectId, code);
        return edit(
                objectId,
                fields -> fields.stream().filter(f -> !f.id().equals(id)).toList(),
                List.of(id));
    }

    DataCenter.PublishPlan plan(String objectId) {
        DataCenter.Design design = designs.get(objectId);
        return publisher.plan(
                new DataCenter.Revision(objectId, design.draft().lockVersion(), null), owner);
    }

    /** 执行计划；需要确认暂停的应用一并确认。 */
    DataCenter.Execution publish(DataCenter.PublishPlan plan, String reason, long actor) {
        assertThat(plan.checks()).noneMatch(DataCenter.Check::blocking);
        DataCenter.Execution execution =
                publisher.execute(
                        new DataCenter.ExecutePlan(
                                plan.id(),
                                reason,
                                List.of(),
                                plan.applicationUpgrades().stream()
                                        .map(ObjectApplicationUpgrade.Impact::applicationId)
                                        .toList()),
                        actor);
        assertThat(execution.state()).isEqualTo("SUCCEEDED");
        return execution;
    }

    /** 给对象加一个普通文本字段并发布；返回本次发布计划的 ID。 */
    String publishNewField(String objectId, String code, String reason) {
        addField(objectId, code, "新字段" + code);
        DataCenter.PublishPlan plan = plan(objectId);
        publish(plan, reason, owner);
        return plan.id();
    }

    // ── 应用 ──

    ApplicationCenter.Resource resource(String id, String kind, Object config) {
        return new ApplicationCenter.Resource(
                id,
                kind,
                "follow_" + id,
                id,
                mapper.convertValue(
                        config,
                        new com.fasterxml.jackson.core.type.TypeReference<
                                Map<String, Object>>() {}));
    }

    ApplicationCenter.Resource view(String id, String objectId) {
        return resource(
                id,
                "VIEW",
                new ApplicationUi.View(
                        objectId,
                        List.of(fieldId(objectId, "name")),
                        Map.of(),
                        null,
                        false,
                        10,
                        null));
    }

    /** 保存应用草稿（不发布）。 */
    ApplicationCenter.Detail draft(
            String suffix,
            String name,
            long creator,
            List<ApplicationCenter.Resource> resources,
            String... objectIds) {
        List<ApplicationCenter.ObjectReference> references = new ArrayList<>();
        for (String objectId : objectIds) references.add(reference(objectId));
        return apps.save(
                new ApplicationCenter.Save(
                        null,
                        null,
                        support.prefix + suffix + serial++,
                        name,
                        null,
                        null,
                        new ApplicationCenter.Definition(references, resources)),
                creator);
    }

    /** 保存并发布应用，返回应用 ID。 */
    String app(String suffix, String name, String... objectIds) {
        return app(suffix, name, owner, List.of(), objectIds);
    }

    String app(
            String suffix,
            String name,
            long creator,
            List<ApplicationCenter.Resource> resources,
            String... objectIds) {
        ApplicationCenter.Detail saved = draft(suffix, name, creator, resources, objectIds);
        apps.publish(
                new ApplicationCenter.Revision(
                        saved.application().id(), saved.application().revision(), "夹具发布"),
                creator);
        return saved.application().id();
    }

    int publishedVersion(String app) {
        return jdbc.queryForObject(
                "SELECT published_version FROM public.nocode_application WHERE id=?",
                Integer.class,
                Long.valueOf(app));
    }

    int versionCount(String app) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM public.nocode_application_version WHERE application_id=?",
                Integer.class,
                Long.valueOf(app));
    }

    /** 已发布快照里对某个对象的引用，直接读库。 */
    JsonNode publishedReference(String app, String objectId) {
        return reference(
                jdbc.queryForObject(
                        "SELECT (v.definition_json->'definition'->'objects')::text FROM"
                                + " public.nocode_application a JOIN"
                                + " public.nocode_application_version v ON v.application_id=a.id"
                                + " AND v.version_no=a.published_version WHERE a.id=?",
                        String.class,
                        Long.valueOf(app)),
                objectId);
    }

    /** 草稿里对某个对象的引用，直接读库。 */
    JsonNode draftReference(String app, String objectId) {
        return reference(
                jdbc.queryForObject(
                        "SELECT (design_json->'objects')::text FROM public.nocode_application WHERE"
                                + " id=?",
                        String.class,
                        Long.valueOf(app)),
                objectId);
    }

    private JsonNode reference(String objects, String objectId) {
        try {
            for (JsonNode item : mapper.readTree(objects))
                if (objectId.equals(item.path("objectId").asText())) return item;
            return null;
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    String publishReason(String app) {
        return jdbc.queryForObject(
                "SELECT v.reason FROM public.nocode_application a JOIN"
                        + " public.nocode_application_version v ON v.application_id=a.id AND"
                        + " v.version_no=a.published_version WHERE a.id=?",
                String.class,
                Long.valueOf(app));
    }

    Map<String, Object> state(String app, String objectId) {
        List<Map<String, Object>> rows =
                jdbc.queryForList(
                        "SELECT enabled,state,pending_version,pending_code,pending_reason,"
                                + "followed_version,lock_version FROM"
                                + " public.nocode_application_object_follow WHERE application_id=?"
                                + " AND object_id=?",
                        Long.valueOf(app),
                        Long.valueOf(objectId));
        return rows.isEmpty() ? null : rows.getFirst();
    }

    List<Map<String, Object>> logs(String app, String objectId) {
        return jdbc.queryForList(
                "SELECT from_version,to_version,application_version_before,"
                        + "application_version_after,outcome,pending_code,reason,trigger_kind,"
                        + "plan_id::text AS plan_id,creator FROM"
                        + " public.nocode_application_object_follow_log WHERE application_id=? AND"
                        + " object_id=? ORDER BY id",
                Long.valueOf(app),
                Long.valueOf(objectId));
    }

    long dependencyRows(String app, String scope) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM public.nocode_resource_dependency WHERE source_key=?",
                Long.class,
                "application:" + app + ":" + scope);
    }

    // ── 在途审批（虚构行）──

    /** 一条运行中的「发起流程」类审批。 */
    void runningProcess(String app, String objectId) {
        jdbc.update(
                "INSERT INTO public.nocode_record_process(application_id,application_version,"
                        + "object_id,object_version,record_id,action_id,name,business_key,"
                        + "process_definition_id,process_definition_key,status,creator,updater)"
                        + " VALUES (?,?,?,?,?,?,?,?,?,?,'RUNNING',?,?)",
                Long.valueOf(app),
                publishedVersion(app),
                Long.valueOf(objectId),
                objects.getVersion(objectId, null).versionNo(),
                "r" + serial,
                "action",
                "夹具流程",
                support.prefix + "bk" + serial++,
                "definition",
                "key",
                Long.toString(owner),
                Long.toString(owner));
    }

    void finishProcesses(String app) {
        jdbc.update(
                "UPDATE public.nocode_record_process SET status='APPROVED' WHERE application_id=?",
                Long.valueOf(app));
    }

    /** 一条未完结的办理申请（连同它依赖的草稿与提交材料）。 */
    void pendingHandling(String app, String objectId) {
        String id = UUID.randomUUID().toString();
        jdbc.update(
                "INSERT INTO public.nocode_work_draft(id,source_type,source_id,state,resource_json,"
                        + "object_id,values_json,creator,updater) VALUES"
                        + " (?,'TASK_ENTRY',?,'SUBMITTED',CAST(? AS jsonb),?,'{}'::jsonb,?,?)",
                id,
                support.prefix,
                "{\"applicationId\":\"" + app + "\"}",
                objectId,
                Long.toString(owner),
                Long.toString(owner));
        jdbc.update(
                "INSERT INTO public.nocode_work_submission(id,draft_id,idempotency_key,"
                        + "request_digest,material_json,creator,updater) VALUES"
                        + " (?,?,?,?,'{}'::jsonb,?,?)",
                id,
                id,
                id,
                "digest",
                Long.toString(owner),
                Long.toString(owner));
        jdbc.update(
                "INSERT INTO public.nocode_handling_request(id,application_id,application_name,"
                    + "application_version,object_id,object_name,operation,name,request_key,"
                    + "request_digest,definition_checksum,definition_json,submission_id,process_definition_id,process_definition_key,status,creator,updater)"
                    + " VALUES (CAST(? AS"
                    + " uuid),?,?,?,?,?,'CREATE',?,?,?,?,'{}'::jsonb,?,?,?,'PENDING',?,?)",
                id,
                Long.valueOf(app),
                "夹具应用",
                publishedVersion(app),
                Long.valueOf(objectId),
                "夹具对象",
                "夹具申请",
                id,
                "digest",
                "checksum",
                id,
                "definition",
                "key",
                Long.toString(owner),
                Long.toString(owner));
    }

    void finishHandlings(String app) {
        jdbc.update(
                "UPDATE public.nocode_handling_request SET status='APPROVED' WHERE"
                        + " application_id=?",
                Long.valueOf(app));
    }

    /** 只清理本夹具前缀拥有的应用与对象。 */
    void cleanup() {
        writeFailure.clear();
        configure(true, "strict");
        List<Long> ids =
                jdbc.queryForList(
                        "SELECT id FROM public.nocode_application WHERE app_code LIKE ?",
                        Long.class,
                        support.prefix + "%");
        for (Long id : ids) {
            List<String> submissions =
                    jdbc.queryForList(
                            "SELECT submission_id FROM public.nocode_handling_request WHERE"
                                    + " application_id=?",
                            String.class,
                            id);
            jdbc.update("DELETE FROM public.nocode_handling_request WHERE application_id=?", id);
            for (String submission : submissions) {
                jdbc.update("DELETE FROM public.nocode_work_submission WHERE id=?", submission);
                jdbc.update("DELETE FROM public.nocode_work_draft WHERE id=?", submission);
            }
            jdbc.update("DELETE FROM public.nocode_record_process WHERE application_id=?", id);
            for (String table :
                    List.of(
                            "nocode_object_application_grant_log",
                            "nocode_object_application_grant",
                            "nocode_task_entry_access",
                            "nocode_application_access",
                            "nocode_application_version"))
                jdbc.update("DELETE FROM public." + table + " WHERE application_id=?", id);
            jdbc.update("DELETE FROM public.nocode_application WHERE id=?", id);
        }
        support.clean();
    }
}

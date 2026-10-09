package com.richuang.os.nocode.tools;

import static com.richuang.os.nocode.tools.NocodeIntegrationSupport.*;

import static org.assertj.core.api.Assertions.*;

import com.richuang.os.framework.common.biz.system.permission.PermissionCommonApi;
import com.richuang.os.framework.common.exception.ServiceException;
import com.richuang.os.nocode.api.*;
import com.richuang.os.nocode.api.ApplicationRecords.*;
import com.richuang.os.nocode.application.service.authorization.ApplicationAuthorizationService;
import com.richuang.os.nocode.runtime.dal.dataobject.RecordFolderSourceDO;
import com.richuang.os.nocode.runtime.dal.mapper.RecordFolderBindingMapper;
import com.richuang.os.nocode.runtime.dal.mapper.RecordFolderSourceMapper;
import com.richuang.os.nocode.runtime.service.folder.RecordFolderAutoCreator;
import com.richuang.os.nocode.runtime.service.folder.RecordFolderConfigService;
import com.richuang.os.nocode.runtime.service.folder.RecordFolderNamer;
import com.richuang.os.nocode.runtime.service.folder.RecordFolderResolver;
import com.richuang.os.nocode.runtime.service.folder.RecordFolderService;
import com.richuang.os.nocode.runtime.service.live.RecordChangeCollector;
import com.richuang.os.nocode.runtime.service.record.RecordQueryAccess;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 记录文件夹集成用例共用的虚构夹具：真实的网盘实现（{@link DriveFolderTestBed}）+ 无代码侧的文件夹服务，「合同」「凭证」两个对象、应用与成员。
 * 只创建并清理本次随机前缀的对象、应用与网盘空间，数据全部虚构。
 *
 * <p>人物：{@link #OWNER} 是应用负责人、对象管理员、网盘管理员；{@link #JIA} 能改凭证；{@link #YI} 只能看；{@link #BING}
 * 只能看自己建的（别人建的记录他看不到）；{@link #DING} 能看凭证但看不到关联字段；{@link #JI} 与甲一样能改凭证。
 */
final class RecordFolderFixture {
    static final long OWNER = RecordLiveFixture.OWNER;
    static final long JIA = 30011L;
    static final long YI = 30012L;
    static final long BING = 30013L;
    static final long DING = 30014L;
    static final long JI = 30015L;

    /** 对象管理员（能进数据维护入口），但不是网盘管理员、也不是应用成员。 */
    static final long WU = 30016L;

    /** 能改全部凭证的另一位成员。 */
    static final long GENG = 30017L;

    final DriveFolderTestBed bed;
    final RecordLiveFixture live = new RecordLiveFixture();
    final RecordFolderConfigService configs;
    final RecordFolderService folders;
    final RecordFolderResolver resolver;
    final RecordFolderAutoCreator autoCreator;
    final RecordFolderSourceMapper sources;
    final RecordQueryAccess records = servicesContext.getBean(RecordQueryAccess.class);
    private static final java.util.concurrent.atomic.AtomicInteger SPACES =
            new java.util.concurrent.atomic.AtomicInteger();
    private int serial;

    /** 每个测试类建一次：在共享的无代码容器之下再开一个带真实网盘实现与文件夹服务的容器，并把后台建立组件接到记录变更登记簿上。 */
    static DriveFolderTestBed bed() {
        DriveFolderTestBed bed =
                DriveFolderTestBed.open(
                        servicesContext,
                        context -> {
                            context.registerBean(
                                    RecordFolderSourceMapper.class,
                                    () -> session.getMapper(RecordFolderSourceMapper.class));
                            context.registerBean(
                                    RecordFolderBindingMapper.class,
                                    () -> session.getMapper(RecordFolderBindingMapper.class));
                            context.register(
                                    RecordFolderNamer.class,
                                    RecordFolderResolver.class,
                                    RecordFolderConfigService.class,
                                    RecordFolderAutoCreator.class,
                                    RecordFolderService.class);
                        });
        servicesContext
                .getBean(RecordChangeCollector.class)
                .listen(bed.context.getBean(RecordFolderAutoCreator.class));
        PermissionCommonApi permissions = servicesContext.getBean(PermissionCommonApi.class);
        org.mockito.Mockito.when(permissions.hasAnyPermissions(OWNER, "drive:space:update"))
                .thenReturn(true);
        for (String permission : List.of("nocode:object:query", "nocode:object:manage"))
            org.mockito.Mockito.when(permissions.hasAnyPermissions(WU, permission))
                    .thenReturn(true);
        return bed;
    }

    RecordFolderFixture(DriveFolderTestBed bed) {
        this.bed = bed;
        configs = bed.context.getBean(RecordFolderConfigService.class);
        folders = bed.context.getBean(RecordFolderService.class);
        resolver = bed.context.getBean(RecordFolderResolver.class);
        autoCreator = bed.context.getBean(RecordFolderAutoCreator.class);
        sources = bed.context.getBean(RecordFolderSourceMapper.class);
        bed.drive.reset();
    }

    void cleanup() {
        autoCreator.drain(10_000);
        bed.drive.reset();
        String owned = "(SELECT id::text FROM public.nocode_object WHERE object_code LIKE ?)";
        jdbc.update(
                "DELETE FROM public.nocode_record_folder_binding WHERE object_id IN " + owned,
                live.fixture.prefix + "%");
        jdbc.update(
                "DELETE FROM public.nocode_record_folder_source WHERE object_id IN " + owned,
                live.fixture.prefix + "%");
        live.cleanup();
    }

    // ========== 对象、应用、成员 ==========

    /** 「合同」：编号、名称（标题）、签订日期、类型（单选：A 施工 / B 采购）。 */
    DataCenter.Definition contract() {
        List<FieldDefinition> fields =
                List.of(
                        field("code", "编号", "TEXT", 0),
                        field("name", "名称", "TEXT", 1),
                        field("signed", "签订日期", "DATE", 2),
                        field("kind", "类型", "SELECT", 3));
        Map<String, DataCenter.FieldOptions> options = new LinkedHashMap<>();
        options.put(
                "kind",
                DataCenter.FieldOptions.copyOf(DataCenter.FieldOptions.defaults())
                        .options(
                                List.of(
                                        new DataCenter.Option("A", "施工", false),
                                        new DataCenter.Option("B", "采购", false)))
                        .build());
        SaveObjectDraft base = live.fixture.createRequest("contract" + serial++);
        return live.publish(
                designs.save(
                        new DataCenter.SaveDesign(
                                new SaveObjectDraft(
                                        null,
                                        null,
                                        base.objectCode(),
                                        "合同",
                                        null,
                                        base.tableName(),
                                        "name",
                                        fields,
                                        List.of()),
                                DataCenter.Settings.defaults(),
                                options,
                                List.of(),
                                List.of(),
                                List.of()),
                        OWNER));
    }

    /** 指向 target 的对象：名称（标题）+ 单值关联「所属」。label 是对象名。 */
    DataCenter.Definition related(DataCenter.Definition target, String label) {
        SaveObjectDraft base = live.fixture.createRequest("related" + serial++);
        return live.publish(
                designs.save(
                        new DataCenter.SaveDesign(
                                new SaveObjectDraft(
                                        null,
                                        null,
                                        base.objectCode(),
                                        label,
                                        null,
                                        base.tableName(),
                                        "name",
                                        List.of(field("name", "名称", "TEXT", 0)),
                                        List.of()),
                                DataCenter.Settings.defaults(),
                                Map.of(),
                                List.of(
                                        new DataCenter.Relation(
                                                null,
                                                "parent",
                                                "所属",
                                                "REFERENCE",
                                                target.objectId(),
                                                null,
                                                null,
                                                false,
                                                "RESTRICT")),
                                List.of(),
                                List.of()),
                        OWNER));
    }

    /** 「凭证」：名称 + 单值关联「所属」指向合同。 */
    DataCenter.Definition voucher(DataCenter.Definition contract) {
        return related(contract, "凭证");
    }

    private static FieldDefinition field(String code, String name, String type, int sort) {
        return new FieldDefinition(
                code,
                null,
                code,
                name,
                type,
                "TEXT".equals(type) ? 200 : null,
                null,
                null,
                false,
                false,
                sort);
    }

    /** 单值关联「所属」的关联列字段 ID。 */
    String relationField(DataCenter.Definition d) {
        return d.relations().stream()
                .filter(relation -> relation.code().equals("parent"))
                .findFirst()
                .orElseThrow()
                .fieldId();
    }

    String app(DataCenter.Definition... definitions) {
        return live.app(List.of(), definitions);
    }

    /** 全部字段可读写的授权；actions 给定，scope 为 ALL 或 OWN；hidden 是不可读的字段 ID。 */
    ApplicationAuthorization.ObjectGrant grant(
            DataCenter.Definition d, Set<String> actions, String scope, String... hidden) {
        Set<String> fields =
                d.fields().stream().map(FieldDefinition::id).collect(Collectors.toSet());
        fields.removeAll(Set.of(hidden));
        return new ApplicationAuthorization.ObjectGrant(
                d.objectId(),
                actions,
                scope,
                fields,
                fields,
                Set.of(),
                Set.of(),
                Set.of(),
                Set.of());
    }

    ApplicationAuthorization.ObjectGrant editor(DataCenter.Definition d) {
        return grant(d, Set.of("READ", "CREATE", "UPDATE", "DELETE"), "ALL");
    }

    ApplicationAuthorization.ObjectGrant viewer(DataCenter.Definition d) {
        return grant(d, Set.of("READ"), "ALL");
    }

    /** 能看全部、只能改「名称」等于给定值的那条记录。 */
    ApplicationAuthorization.ObjectGrant editorOf(DataCenter.Definition d, String name) {
        ApplicationAuthorization.ObjectGrant base = grant(d, Set.of("READ", "UPDATE"), "ALL");
        return new ApplicationAuthorization.ObjectGrant(
                base.objectId(),
                base.actions(),
                base.scope(),
                base.readFields(),
                base.writeFields(),
                base.readDetails(),
                base.writeDetails(),
                base.readRelations(),
                base.writeRelations(),
                Map.of(
                        "UPDATE",
                        new DataScope(
                                "AND",
                                List.of(new DataScope.Condition(live.field(d, "name"), "eq", name)),
                                List.of())),
                Set.of());
    }

    /** 整份替换应用的成员授权。 */
    void members(String app, Map<Long, List<ApplicationAuthorization.ObjectGrant>> grants) {
        var authorization = servicesContext.getBean(ApplicationAuthorizationService.class);
        List<ApplicationAuthorization.Member> members = new ArrayList<>();
        grants.forEach(
                (user, objects) ->
                        members.add(
                                new ApplicationAuthorization.Member(
                                        "USER", Long.toString(user), objects)));
        authorization.save(
                new ApplicationAuthorization.Save(app, authorization.get(app).revision(), members),
                OWNER);
    }

    // ========== 记录 ==========

    Row create(String app, DataCenter.Definition d, Map<String, Object> byCode, long actor) {
        return live.runtime
                .save(new Save(app, d.objectId(), null, null, values(d, byCode), null), actor)
                .record();
    }

    Row create(String app, DataCenter.Definition d, Map<String, Object> byCode) {
        return create(app, d, byCode, OWNER);
    }

    Row update(String app, DataCenter.Definition d, String id, Map<String, Object> byCode) {
        Row current = live.runtime.get(app, d.objectId(), id, OWNER).record();
        return live.runtime
                .save(
                        new Save(
                                app, d.objectId(), id, current.revision(), values(d, byCode), null),
                        OWNER)
                .record();
    }

    /** 键可以是字段编码，也可以直接是字段 ID（关联列）。 */
    Map<String, Object> values(DataCenter.Definition d, Map<String, Object> byCode) {
        Map<String, Object> result = new LinkedHashMap<>();
        byCode.forEach(
                (code, value) ->
                        result.put(
                                d.fields().stream()
                                        .filter(field -> field.code().equals(code))
                                        .map(FieldDefinition::id)
                                        .findFirst()
                                        .orElse(code),
                                value));
        return result;
    }

    /** 把一条记录标成「审批中」（流程运行中）。 */
    void running(String app, DataCenter.Definition d, String recordId) {
        jdbc.update(
                "INSERT INTO public.nocode_record_process(application_id, application_version,"
                        + " object_id, object_version, record_id, action_id, name, business_key,"
                        + " process_definition_id, process_definition_key, status, creator,"
                        + " updater) VALUES (?, 1, ?, 1, ?, 'act', '审批', ?, 'approval:1',"
                        + " 'approval', 'RUNNING', '10001', '10001')",
                Long.valueOf(app),
                Long.valueOf(d.objectId()),
                recordId,
                "nocode:" + UUID.randomUUID());
    }

    // ========== 网盘与配置 ==========

    /** 业务空间里的一个普通文件夹，由网盘管理员（OWNER）建；返回 [空间, 文件夹]。 */
    long[] bizFolder(String name) {
        long space = bed.space("B" + SPACES.incrementAndGet(), "BIZ", null);
        return new long[] {space, bed.folder(space, 0L, name, OWNER)};
    }

    static RecordFolders.SourceInput folderSource(
            long[] folder, String placement, String label, String createMode) {
        return new RecordFolders.SourceInput(
                null,
                "FOLDER",
                placement,
                label,
                folder[0],
                folder[1],
                null,
                null,
                createMode,
                null);
    }

    static RecordFolders.SourceInput relationSource(
            String relationFieldId,
            String targetSourceId,
            String placement,
            String label,
            String createMode) {
        return new RecordFolders.SourceInput(
                null,
                "RELATION",
                placement,
                label,
                null,
                null,
                relationFieldId,
                targetSourceId,
                createMode,
                null);
    }

    /** 整份保存对象的文件夹来源，返回保存后的清单（按页签顺序）。 */
    List<RecordFolders.Source> configure(
            DataCenter.Definition d, RecordFolders.SourceInput... inputs) {
        return configs.save(new RecordFolders.SaveConfig(d.objectId(), List.of(inputs)), OWNER);
    }

    RecordFolderSourceDO source(RecordFolders.Source saved) {
        return sources.selectActive(Long.valueOf(saved.id()));
    }

    RecordFolders.EntryQuery query(
            String app, DataCenter.Definition d, String recordId, RecordFolders.Source source) {
        return new RecordFolders.EntryQuery(
                app, d.objectId(), recordId, source.id(), null, null, null, null, null, null);
    }

    static RecordFolders.EntryQuery with(
            RecordFolders.EntryQuery q,
            Long id,
            Long parentId,
            Long targetParentId,
            List<Long> ids,
            String name) {
        return new RecordFolders.EntryQuery(
                q.applicationId(),
                q.objectId(),
                q.recordId(),
                q.sourceId(),
                id,
                parentId,
                targetParentId,
                ids,
                name,
                null);
    }

    RecordFolders.Entry upload(RecordFolders.EntryQuery q, long actor, String name) {
        byte[] bytes = name.getBytes(StandardCharsets.UTF_8);
        return folders.upload(
                new RecordFolders.UploadQuery(
                        q.applicationId(),
                        q.objectId(),
                        q.recordId(),
                        q.sourceId(),
                        q.parentId(),
                        name,
                        "text/plain",
                        bytes.length),
                actor,
                new ByteArrayInputStream(bytes));
    }

    void trash(RecordFolders.EntryQuery q, long actor, Long... ids) {
        folders.trash(with(q, null, null, null, List.of(ids), null), actor);
    }

    /** 记录在某来源下的子文件夹对应关系：返回网盘节点编号；没有返回 null。 */
    Long boundEntry(DataCenter.Definition d, String recordId, RecordFolders.Source source) {
        return jdbc
                .query(
                        "SELECT entry_id FROM public.nocode_record_folder_binding WHERE"
                                + " object_id=? AND record_id=? AND source_id=? AND deleted=0",
                        (row, index) -> row.getLong(1),
                        d.objectId(),
                        recordId,
                        Long.valueOf(source.id()))
                .stream()
                .findFirst()
                .orElse(null);
    }

    int bindings(DataCenter.Definition d) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM public.nocode_record_folder_binding WHERE object_id=? AND"
                        + " deleted=0",
                Integer.class,
                d.objectId());
    }

    /** 文件夹下未进回收站的直接子节点名称。 */
    List<String> children(long folder) {
        return jdbc.queryForList(
                "SELECT name FROM public.drive_entry WHERE parent_id=? AND trash_state='NORMAL'"
                        + " AND deleted=0 ORDER BY name",
                String.class,
                folder);
    }

    static void rejected(String message, ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(
                        ServiceException.class,
                        error -> assertThat(error.getMessage()).isEqualTo(message));
    }
}

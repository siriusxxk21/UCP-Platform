package com.lingan.ucp.nocode.runtime.service.folder;

import static com.lingan.ucp.nocode.api.NocodeErrorCodes.NOT_FOUND;
import static com.lingan.ucp.nocode.api.NocodeErrorCodes.invalid;

import com.lingan.ucp.framework.common.exception.ServiceException;
import com.lingan.ucp.module.drive.api.folder.DriveFolderApi;
import com.lingan.ucp.module.drive.api.folder.dto.DriveFolderContent;
import com.lingan.ucp.module.drive.api.folder.dto.DriveFolderNode;
import com.lingan.ucp.module.drive.api.folder.dto.DriveFolderScope;
import com.lingan.ucp.nocode.api.ApplicationRecords.Row;
import com.lingan.ucp.nocode.api.DataCenter;
import com.lingan.ucp.nocode.api.DataObjectApi;
import com.lingan.ucp.nocode.api.RecordFolders;
import com.lingan.ucp.nocode.enums.ApplicationActionEnum;
import com.lingan.ucp.nocode.enums.RecordFolderCreateModeEnum;
import com.lingan.ucp.nocode.enums.RecordFolderKindEnum;
import com.lingan.ucp.nocode.runtime.dal.dataobject.RecordFolderSourceDO;
import com.lingan.ucp.nocode.runtime.dal.mapper.RecordFolderSourceMapper;
import com.lingan.ucp.nocode.runtime.service.maintenance.ObjectDataMaintenanceService;
import com.lingan.ucp.nocode.runtime.service.record.RecordQueryAccess;

import jakarta.annotation.Resource;

import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * 记录文件夹：表单下方嵌入的文件夹浏览。
 *
 * <p>没有令牌、没有会话：每个请求都带「凭据」（应用、对象、记录、来源），服务端每次重新算出根在哪、角色是什么——能看这条记录 ⇒ 可查看，能改这条记录 ⇒
 * 可编辑（不叠加流程保护：文件夹操作与审批状态无关）。客户端不提交根的编号，接口里根就是 0；来源键由服务端从凭据算，客户端提交不了。
 *
 * <p>算出限定子树之后，文件操作全部转给网盘的 {@link DriveFolderApi}：越界由网盘唯一的权限判断点拦，来源规则由网盘侧的实现查。写操作在这里不按角色拦
 * ——交给网盘按「凭记录的角色」与「本人网盘角色」取大来判；唯一例外是还没建的文件夹：只有能改这条记录的人能触发建立。
 */
@Service
public class RecordFolderService {
    static final String HIDDEN = "记录不存在或不可访问";
    static final String UNAVAILABLE = "文件夹不存在或已不可用";

    private static final String VIEWER = "VIEWER";
    private static final String EDITOR = "EDITOR";
    private static final String MANAGER = "MANAGER";

    @Resource private RecordQueryAccess records;
    @Resource private RecordFolderSourceMapper sources;
    @Resource private RecordFolderResolver resolver;
    @Resource private RecordFolderConfigService configs;
    @Resource private RecordFolderAutoCreator autoCreator;
    @Resource private DriveFolderApi driveFolders;
    @Resource private ObjectDataMaintenanceService maintenance;
    @Resource private DataObjectApi objects;

    /** 一次请求鉴权后的结果：哪个来源、读到的记录、凭记录换来的角色、来源键 */
    private record Gate(RecordFolderSourceDO source, Row row, String role, String originKey) {}

    /** 表单下方的页签：逐个来源解析；不同步建任何东西 */
    public RecordFolders.Opened open(RecordFolders.OpenQuery query, long actor) {
        if (query == null) throw invalid("文件夹请求不能为空");
        String application = blankToNull(query.applicationId());
        return enter(
                application,
                actor,
                () -> {
                    Row row =
                            records.authorizedRow(
                                    application, query.objectId(), query.recordId(), actor);
                    String role = role(application, row);
                    DataCenter.Definition definition = null;
                    List<RecordFolders.Tab> tabs = new ArrayList<>();
                    boolean pendingOnSave = false;
                    for (RecordFolderSourceDO source : sources.selectByObject(query.objectId())) {
                        // 看不到那个关联字段就不该知道它关联了谁：这个页签不出现，也不报错
                        if (!relationReadable(source, row)) continue;
                        if (definition == null) definition = objects.getPublished(query.objectId());
                        RecordFolderResolver.Target target =
                                resolver.resolve(
                                        source, query.objectId(), query.recordId(), row, actor);
                        pendingOnSave =
                                pendingOnSave
                                        || target.pending()
                                                && RecordFolderCreateModeEnum.ON_SAVE.matches(
                                                        source.getCreateMode());
                        boolean writable =
                                EDITOR.equals(role) && (target.ready() || target.pending());
                        tabs.add(
                                new RecordFolders.Tab(
                                        source.getId().toString(),
                                        configs.displayLabel(source, definition),
                                        target.state(),
                                        target.message(),
                                        writable,
                                        writable || target.ready() && driveEditor(target, actor)));
                    }
                    // 「保存时就建」的来源还没建：放进后台队列补上，这里不等、不同步建，不管操作者是什么角色
                    if (pendingOnSave) autoCreator.request(query.objectId(), query.recordId());
                    return new RecordFolders.Opened(tabs);
                });
    }

    public List<RecordFolders.Entry> list(RecordFolders.EntryQuery q, long actor) {
        DriveFolderScope scope = readable(q, actor, true);
        if (scope == null) return List.of();
        return entries(driveFolders.list(scope, actor, parent(q.parentId())));
    }

    public RecordFolders.Entry get(RecordFolders.EntryQuery q, long actor) {
        return entry(driveFolders.get(readable(q, actor, false), actor, q.id()));
    }

    public List<String> path(RecordFolders.EntryQuery q, long actor) {
        return driveFolders.path(readable(q, actor, false), actor, q.id());
    }

    public List<RecordFolders.Entry> search(RecordFolders.EntryQuery q, long actor) {
        DriveFolderScope scope = readable(q, actor, true);
        if (scope == null) return List.of();
        return entries(driveFolders.search(scope, actor, q.name(), q.limit()));
    }

    public Long createFolder(RecordFolders.EntryQuery q, long actor) {
        DriveFolderScope scope = writable(q, actor);
        return driveFolders.createFolder(scope, actor, parent(q.parentId()), q.name());
    }

    /** 上传：凭据校验与（需要时的）建根在自己的短事务里提交之后才传内容，上传内容期间不持有数据库事务。 */
    public RecordFolders.Entry upload(
            RecordFolders.UploadQuery q, long actor, InputStream content) {
        if (q == null) throw invalid("文件夹请求不能为空");
        DriveFolderScope scope =
                scope(
                        blankToNull(q.applicationId()),
                        q.objectId(),
                        q.recordId(),
                        q.sourceId(),
                        actor,
                        Pending.CREATE);
        return entry(
                driveFolders.upload(
                        scope,
                        actor,
                        parent(q.parentId()),
                        q.fileName(),
                        q.contentType(),
                        q.size(),
                        content));
    }

    public void rename(RecordFolders.EntryQuery q, long actor) {
        driveFolders.rename(readable(q, actor, false), actor, q.id(), q.name());
    }

    public void move(RecordFolders.EntryQuery q, long actor) {
        driveFolders.move(readable(q, actor, false), actor, q.id(), parent(q.targetParentId()));
    }

    public Long copy(RecordFolders.EntryQuery q, long actor) {
        return driveFolders.copy(
                readable(q, actor, false), actor, q.id(), parent(q.targetParentId()));
    }

    public void trash(RecordFolders.EntryQuery q, long actor) {
        driveFolders.trash(readable(q, actor, false), actor, q.ids() == null ? List.of() : q.ids());
    }

    public List<RecordFolders.Entry> trashList(RecordFolders.EntryQuery q, long actor) {
        DriveFolderScope scope = readable(q, actor, true);
        if (scope == null) return List.of();
        return entries(driveFolders.trashList(scope, actor));
    }

    public RecordFolders.Entry restore(RecordFolders.EntryQuery q, long actor) {
        return entry(driveFolders.restore(readable(q, actor, false), actor, q.id()));
    }

    /** 内容元信息：带着解析好的根，供控制器随后按偏移量打开内容流；不序列化给客户端 */
    public RecordFolders.Content content(RecordFolders.ContentQuery q, long actor) {
        if (q == null) throw invalid("文件夹请求不能为空");
        DriveFolderScope scope =
                scope(
                        blankToNull(q.applicationId()),
                        q.objectId(),
                        q.recordId(),
                        q.sourceId(),
                        actor,
                        Pending.REFUSE);
        DriveFolderContent content = driveFolders.contentInfo(scope, actor, q.id());
        return new RecordFolders.Content(
                content.entryId(),
                content.name(),
                content.mimeType(),
                content.size(),
                content.length(),
                scope.spaceId(),
                scope.rootEntryId(),
                scope.role(),
                scope.originKey());
    }

    /** 打开内容流：授权链已由 content(...) 校验；网盘侧按同一棵限定子树再判一次。调用方负责关闭 */
    public InputStream contentStream(RecordFolders.Content content, long actor, long offset) {
        return driveFolders.openContent(
                new DriveFolderScope(
                        content.spaceId(),
                        content.rootEntryId(),
                        content.role(),
                        content.originKey()),
                actor,
                content.entryId(),
                offset);
    }

    // ========== 鉴权与解析 ==========

    /** 文件夹还没建时怎么办 */
    private enum Pending {
        /** 列表类：当作空文件夹 */
        EMPTY,
        /** 针对已有节点的操作：文件夹不存在 */
        REFUSE,
        /** 新建与上传：能改这条记录的人先把文件夹建出来 */
        CREATE
    }

    /** 列表类（emptyWhenPending）返回 null 表示文件夹还没建、当作空；其余操作在文件夹还没建时拒绝 */
    private DriveFolderScope readable(
            RecordFolders.EntryQuery q, long actor, boolean emptyWhenPending) {
        if (q == null) throw invalid("文件夹请求不能为空");
        return scope(
                blankToNull(q.applicationId()),
                q.objectId(),
                q.recordId(),
                q.sourceId(),
                actor,
                emptyWhenPending ? Pending.EMPTY : Pending.REFUSE);
    }

    private DriveFolderScope writable(RecordFolders.EntryQuery q, long actor) {
        if (q == null) throw invalid("文件夹请求不能为空");
        return scope(
                blankToNull(q.applicationId()),
                q.objectId(),
                q.recordId(),
                q.sourceId(),
                actor,
                Pending.CREATE);
    }

    /** 每个方法的第一步：鉴权、解析（需要且允许时建根），得到这次调用的限定子树 */
    private DriveFolderScope scope(
            String application,
            String objectId,
            String recordId,
            String sourceId,
            long actor,
            Pending pending) {
        return enter(
                application,
                actor,
                () -> {
                    Gate gate = gate(application, objectId, recordId, sourceId, actor);
                    RecordFolderResolver.Target target =
                            resolver.resolve(gate.source(), objectId, recordId, gate.row(), actor);
                    if (target.pending()) {
                        if (pending == Pending.EMPTY) return null;
                        if (pending == Pending.REFUSE) throw invalid(UNAVAILABLE);
                        if (!EDITOR.equals(gate.role())) throw invalid("没有此记录的修改权限");
                        target =
                                resolver.ensure(
                                        gate.source(), objectId, recordId, gate.row(), actor);
                    }
                    if (!target.ready())
                        throw invalid(target.message() == null ? UNAVAILABLE : target.message());
                    return new DriveFolderScope(
                            target.spaceId(), target.entryId(), gate.role(), gate.originKey());
                });
    }

    private Gate gate(
            String application, String objectId, String recordId, String sourceId, long actor) {
        // 入口与记录：应用入口校验成员与对象归属，数据维护入口要求对象管理权；看不到这条记录时不透露它是否存在
        Row row = records.authorizedRow(application, objectId, recordId, actor);
        RecordFolderSourceDO source =
                sourceId != null && sourceId.matches("[1-9][0-9]{0,18}")
                        ? sources.selectActive(Long.valueOf(sourceId))
                        : null;
        if (source == null || !source.getObjectId().equals(objectId)) throw hidden();
        if (!relationReadable(source, row)) throw hidden();
        // 来源键永远是凭据里的对象与记录（打开的这张表单），不是关联的那条
        return new Gate(
                source,
                row,
                role(application, row),
                RecordFolderResolver.originKey(objectId, recordId));
    }

    /** 用关联记录的文件夹：这个人必须能读本记录上的那个关联字段 */
    private static boolean relationReadable(RecordFolderSourceDO source, Row row) {
        return !RecordFolderKindEnum.RELATION.matches(source.getKind())
                || row.permissions() != null
                        && row.permissions().readFields().contains(source.getRelationFieldId());
    }

    /** 本人在这个已建好的文件夹上的网盘角色是否达到可编辑；此时线程上没有限定子树，查到的就是写操作判定时与凭记录的角色取大的那一半。 */
    private boolean driveEditor(RecordFolderResolver.Target target, long actor) {
        String own = driveFolders.roleOf(target.spaceId(), target.entryId(), actor);
        return EDITOR.equals(own) || MANAGER.equals(own);
    }

    /** 能改这条记录 ⇒ 可编辑，否则可查看；数据维护入口恒为可编辑。不叠加流程保护。 */
    private static String role(String application, Row row) {
        if (application == null) return EDITOR;
        return row.permissions() != null
                        && row.permissions()
                                .actions()
                                .contains(ApplicationActionEnum.UPDATE.getCode())
                ? EDITOR
                : VIEWER;
    }

    /** 数据维护入口沿用数据对象管理授权（并建立维护上下文），应用入口使用应用发布版本与运行授权 */
    private <T> T enter(String application, long actor, Supplier<T> work) {
        return application == null ? maintenance.read(actor, work) : work.get();
    }

    private static ServiceException hidden() {
        return new ServiceException(NOT_FOUND, HIDDEN);
    }

    private static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text;
    }

    private static Long parent(Long parentId) {
        return parentId == null ? 0L : parentId;
    }

    private static List<RecordFolders.Entry> entries(List<DriveFolderNode> nodes) {
        return nodes.stream().map(RecordFolderService::entry).toList();
    }

    private static RecordFolders.Entry entry(DriveFolderNode node) {
        return new RecordFolders.Entry(
                node.id(),
                node.spaceId(),
                node.parentId(),
                node.name(),
                node.type(),
                node.size(),
                node.mimeType(),
                node.role(),
                node.creator(),
                node.createTime(),
                node.updateTime(),
                node.trashedAt(),
                node.trashedBy(),
                node.modifiable());
    }
}

package com.richuang.os.nocode.runtime.service.folder;

import static com.richuang.os.nocode.api.NocodeErrorCodes.invalid;

import com.richuang.os.module.drive.api.folder.DriveFolderApi;
import com.richuang.os.module.drive.api.folder.dto.DriveFolderInfo;
import com.richuang.os.nocode.api.ApplicationRecords.Row;
import com.richuang.os.nocode.api.DataCenter;
import com.richuang.os.nocode.api.DataObjectApi;
import com.richuang.os.nocode.api.FieldDefinition;
import com.richuang.os.nocode.api.RecordFolders;
import com.richuang.os.nocode.enums.RecordFolderKindEnum;
import com.richuang.os.nocode.enums.RecordFolderPlacementEnum;
import com.richuang.os.nocode.enums.RecordFolderStateEnum;
import com.richuang.os.nocode.runtime.dal.dataobject.RecordFolderBindingDO;
import com.richuang.os.nocode.runtime.dal.dataobject.RecordFolderSourceDO;
import com.richuang.os.nocode.runtime.dal.mapper.RecordFolderBindingMapper;
import com.richuang.os.nocode.runtime.dal.mapper.RecordFolderSourceMapper;
import com.richuang.os.nocode.runtime.service.record.RecordQueryAccess;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;

import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 记录文件夹解析：一个来源对一条记录解析出网盘里的一个真实文件夹，或说明为什么还没有。
 *
 * <p>{@link #resolve} 纯读，不建目录、不写表；{@link #ensure} 把「还没建」的那一段逐层建出来。两者走同一段解析代码，所以两个人同时触发、
 * 后台与前台同时触发都只会建出一个：建之前锁住链上的来源行并重新解析，仍然没有才建。
 *
 * <p>关联的那条记录按系统身份读，不查操作者在对方对象上的权限：调用方已凭「打开的这条记录」完成授权，且能读到那个关联字段。
 */
@Component
public class RecordFolderResolver {
    /** 一次解析最多经过的「用关联记录的文件夹」来源数 */
    public static final int MAX_RELATION_DEPTH = 3;

    static final String FOLDER_DELETED = "文件夹已被删除";
    static final String FOLDER_TRASHED = "文件夹在回收站里，请联系网盘管理员恢复";
    static final String SPACE_DISABLED = "所在空间已停用";
    static final String CONFIG_INVALID = "配置已失效";
    static final String RELATED_MISSING = "关联的记录已不存在";

    @Resource private DriveFolderApi driveFolders;
    @Resource private RecordFolderSourceMapper sources;
    @Resource private RecordFolderBindingMapper bindings;
    @Resource private RecordQueryAccess records;
    @Resource private RecordFolderNamer namer;
    @Resource private DataObjectApi objects;
    @Resource private PlatformTransactionManager manager;

    private TransactionTemplate transaction;

    @PostConstruct
    void initialize() {
        transaction = new TransactionTemplate(manager);
    }

    /**
     * 解析结果
     *
     * @param state READY / PENDING / RELATION_EMPTY / UNAVAILABLE
     * @param spaceId READY：文件夹所在空间
     * @param entryId READY：文件夹的网盘节点编号
     * @param parent PENDING：建的时候在谁下面建（READY，或同样还没建的上一层）
     * @param name PENDING：建的时候叫什么
     * @param message RELATION_EMPTY / UNAVAILABLE：给人看的原因
     */
    public record Target(
            String state, Long spaceId, Long entryId, Target parent, String name, String message) {
        public boolean ready() {
            return RecordFolderStateEnum.READY.matches(state);
        }

        public boolean pending() {
            return RecordFolderStateEnum.PENDING.matches(state);
        }

        static Target ready(Long spaceId, Long entryId) {
            return new Target(
                    RecordFolderStateEnum.READY.getCode(), spaceId, entryId, null, null, null);
        }

        static Target pending(Target parent, String name) {
            return new Target(
                    RecordFolderStateEnum.PENDING.getCode(), null, null, parent, name, null);
        }

        static Target unavailable(String message) {
            return new Target(
                    RecordFolderStateEnum.UNAVAILABLE.getCode(), null, null, null, null, message);
        }

        static Target relationEmpty(String message) {
            return new Target(
                    RecordFolderStateEnum.RELATION_EMPTY.getCode(),
                    null,
                    null,
                    null,
                    null,
                    message);
        }
    }

    /** 来源键：经由哪条记录放进去的。对象编号 + 记录编号，不带来源编号（同一条记录的两个页签指向同一个文件夹时互通）。 */
    public static String originKey(String objectId, String recordId) {
        return objectId + ":" + recordId;
    }

    /** 纯读：不建目录、不写表 */
    public Target resolve(
            RecordFolderSourceDO source, String objectId, String recordId, Row row, long actor) {
        return walk(source, objectId, recordId, row, actor, 0, false);
    }

    /** 把 PENDING 链逐层建出来，返回 READY；非 PENDING 原样返回。自带事务，锁来源行。 */
    public Target ensure(
            RecordFolderSourceDO source, String objectId, String recordId, Row row, long actor) {
        return transaction.execute(
                status -> {
                    Target before = walk(source, objectId, recordId, row, actor, 0, false);
                    if (!before.pending()) return before;
                    // 按链从外到内锁住每个涉及的来源行，再重新解析：别人可能刚建好，仍然没有才建。
                    RecordFolderSourceDO locked = null;
                    List<Long> chain = chain(source);
                    for (int index = chain.size() - 1; index >= 0; index--)
                        locked = sources.lock(chain.get(index));
                    if (locked == null || Boolean.TRUE.equals(locked.getDeleted()))
                        return Target.unavailable(CONFIG_INVALID);
                    return walk(locked, objectId, recordId, row, actor, 0, true);
                });
    }

    /** 自这个来源起沿「用对方的哪个来源」往下的来源编号，自内向外；最多走到层数上限的下一层 */
    private List<Long> chain(RecordFolderSourceDO source) {
        List<Long> ids = new ArrayList<>();
        RecordFolderSourceDO current = source;
        while (current != null
                && ids.size() <= MAX_RELATION_DEPTH
                && !ids.contains(current.getId())) {
            ids.add(current.getId());
            current =
                    RecordFolderKindEnum.RELATION.matches(current.getKind())
                            ? sources.selectActive(current.getTargetSourceId())
                            : null;
        }
        return ids;
    }

    /**
     * 解析一层。create 为真时（只在 {@link #ensure} 的事务与行锁之内）把还没建的子文件夹建出来并登记对应关系。
     *
     * @param depth 已经过的「用关联记录的文件夹」来源数
     */
    private Target walk(
            RecordFolderSourceDO source,
            String objectId,
            String recordId,
            Row row,
            long actor,
            int depth,
            boolean create) {
        Target base;
        if (RecordFolderKindEnum.FOLDER.matches(source.getKind())) {
            base = appointed(source);
        } else {
            DataCenter.Definition definition = published(objectId);
            FieldDefinition field = field(definition, source.getRelationFieldId());
            // 关联字段已不在当前版本里（或不再是主表上的单值关联）：配置失效
            if (field == null
                    || RecordFolders.singleRelation(definition, source.getRelationFieldId())
                            == null) return Target.unavailable(CONFIG_INVALID);
            String related =
                    Objects.toString(row.values().get(source.getRelationFieldId()), "").trim();
            if (related.isEmpty()) return Target.relationEmpty("请先选择「" + field.name() + "」");
            if (depth >= MAX_RELATION_DEPTH) return Target.unavailable(CONFIG_INVALID);
            RecordFolderSourceDO target = sources.selectActive(source.getTargetSourceId());
            // 对方的来源已删除，或对方对象已停用：配置失效
            if (target == null || published(target.getObjectId()) == null)
                return Target.unavailable(CONFIG_INVALID);
            Row relatedRow = records.storedRow(target.getObjectId(), related, actor);
            if (relatedRow == null) return Target.unavailable(RELATED_MISSING);
            base =
                    walk(
                            target,
                            target.getObjectId(),
                            related,
                            relatedRow,
                            actor,
                            depth + 1,
                            create);
        }
        if (RecordFolderPlacementEnum.DIRECT.matches(source.getPlacement())) return base;
        if (!base.ready() && !base.pending()) return base;
        if (base.ready()) {
            long anchor =
                    RecordFolderKindEnum.FOLDER.matches(source.getKind()) ? 0L : base.entryId();
            RecordFolderBindingDO binding =
                    bindings.selectBinding(objectId, recordId, source.getId(), anchor);
            if (binding != null) {
                DriveFolderInfo info = driveFolders.describe(binding.getEntryId());
                if (info != null) return bound(info);
                // 对应关系指向的节点已被彻底删除：当作没有；建新的之前把旧行作废
                if (create) bindings.deleteBinding(binding.getId(), Long.toString(actor));
            }
            String name = name(source, objectId, recordId, row, actor);
            if (!create) return Target.pending(base, name);
            return build(source, objectId, recordId, base, anchor, name, actor);
        }
        return Target.pending(base, name(source, objectId, recordId, row, actor));
    }

    /** FOLDER 来源指定的那个文件夹 */
    private Target appointed(RecordFolderSourceDO source) {
        DriveFolderInfo info = driveFolders.describe(source.getEntryId());
        if (info == null) return Target.unavailable(FOLDER_DELETED);
        if (!info.folder()
                || info.managed()
                || !Objects.equals(info.spaceId(), source.getSpaceId()))
            return Target.unavailable(CONFIG_INVALID);
        return bound(info);
    }

    /** 一个已有的文件夹节点当前能不能用 */
    private static Target bound(DriveFolderInfo info) {
        if (info.trashed()) return Target.unavailable(FOLDER_TRASHED);
        if (!info.spaceEnabled()) return Target.unavailable(SPACE_DISABLED);
        if (!info.folder() || info.managed()) return Target.unavailable(CONFIG_INVALID);
        return Target.ready(info.spaceId(), info.id());
    }

    private Target build(
            RecordFolderSourceDO source,
            String objectId,
            String recordId,
            Target base,
            long anchor,
            String name,
            long actor) {
        // 来源键用这一层自己的记录：凭证触发建出合同的子文件夹时，合同子文件夹记合同的键
        Long entry =
                driveFolders.ensureChild(
                        base.entryId(), name, actor, originKey(objectId, recordId));
        DriveFolderInfo created = driveFolders.describe(entry);
        if (created == null) throw invalid("文件夹不存在或已不可用");
        RecordFolderBindingDO binding = new RecordFolderBindingDO();
        binding.setObjectId(objectId);
        binding.setRecordId(recordId);
        binding.setSourceId(source.getId());
        binding.setAnchorEntryId(anchor);
        binding.setSpaceId(created.spaceId());
        binding.setEntryId(entry);
        binding.setOrigin("AUTO");
        binding.setAutoName(created.name());
        binding.setCreator(Long.toString(actor));
        binding.setUpdater(Long.toString(actor));
        bindings.insert(binding);
        return Target.ready(created.spaceId(), entry);
    }

    /** 按这一层自己的来源的模板起名；名字按记录的存储值算，不受操作者字段权限裁剪 */
    private String name(
            RecordFolderSourceDO source, String objectId, String recordId, Row row, long actor) {
        Row stored = row.permissions() == null ? row : records.storedRow(objectId, recordId, actor);
        if (stored == null) stored = row;
        return namer.name(source, objects.getPublished(objectId), stored, actor);
    }

    /** 对象的当前发布版本；未发布或已停用返回 null */
    private DataCenter.Definition published(String objectId) {
        try {
            return objects.getPublished(objectId);
        } catch (com.richuang.os.framework.common.exception.ServiceException missing) {
            return null;
        }
    }

    private static FieldDefinition field(DataCenter.Definition definition, String fieldId) {
        if (definition == null || fieldId == null) return null;
        return definition.fields().stream()
                .filter(item -> fieldId.equals(item.id()))
                .findFirst()
                .orElse(null);
    }
}

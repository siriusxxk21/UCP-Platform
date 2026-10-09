package com.richuang.os.nocode.runtime.service.folder;

import static com.richuang.os.nocode.api.NocodeErrorCodes.invalid;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.richuang.os.framework.common.exception.ServiceException;
import com.richuang.os.module.drive.api.folder.DriveFolderApi;
import com.richuang.os.module.drive.api.folder.dto.DriveFolderInfo;
import com.richuang.os.nocode.api.ApplicationRecords.Row;
import com.richuang.os.nocode.api.DataCenter;
import com.richuang.os.nocode.api.DataObjectApi;
import com.richuang.os.nocode.api.FieldDefinition;
import com.richuang.os.nocode.api.RecordFolders;
import com.richuang.os.nocode.enums.FieldTypeEnum;
import com.richuang.os.nocode.enums.MemberStateEnum;
import com.richuang.os.nocode.enums.RecordFolderCreateModeEnum;
import com.richuang.os.nocode.enums.RecordFolderKindEnum;
import com.richuang.os.nocode.enums.RecordFolderNamePartEnum;
import com.richuang.os.nocode.enums.RecordFolderPlacementEnum;
import com.richuang.os.nocode.enums.RelationTypeEnum;
import com.richuang.os.nocode.runtime.dal.dataobject.RecordFolderSourceDO;
import com.richuang.os.nocode.runtime.dal.mapper.RecordFolderBindingMapper;
import com.richuang.os.nocode.runtime.dal.mapper.RecordFolderSourceMapper;
import com.richuang.os.nocode.runtime.service.record.RecordQueryAccess;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 记录文件夹配置：对象上的文件夹来源（一行 = 表单下方的一个页签）。
 *
 * <p>配置不放进对象设置、不随对象版本冻结：保存即生效，不需要发布。保存是整份替换，任一条校验不过整份拒绝。来源被别的对象引用时不能删除。
 * 「为已有记录补建文件夹」由前端一页一页驱动，与后台的整对象补扫共用同一段处理一页的代码。
 */
@Service
public class RecordFolderConfigService {
    /** 一个对象最多配置的文件夹来源数 */
    public static final int MAX_SOURCES = 6;

    /** 补建一页的条数：缺省值与上限 */
    public static final int BACKFILL_LIMIT = 100;

    /** 一页补建结果里最多保留的失败明细条数 */
    public static final int FAILURE_SAMPLES = 20;

    static final String INVALID = "文件夹配置无效";
    static final String NO_DRIVE_VIEW = "（你在网盘里没有查看这个文件夹的权限）";

    private static final Logger log = LoggerFactory.getLogger(RecordFolderConfigService.class);

    /** 可以用来给文件夹命名的字段类型；背后是单值关联的字段按「引用」处理，不看这张表 */
    private static final Set<FieldTypeEnum> NAMEABLE =
            Set.of(
                    FieldTypeEnum.TEXT,
                    FieldTypeEnum.AUTO_NUMBER,
                    FieldTypeEnum.INTEGER,
                    FieldTypeEnum.DATE,
                    FieldTypeEnum.DATETIME,
                    FieldTypeEnum.SELECT,
                    FieldTypeEnum.REFERENCE);

    private static final Set<String> SEPARATORS = Set.of("-", "_", " ", " · ", "");

    @Resource private RecordFolderSourceMapper sources;
    @Resource private RecordFolderBindingMapper bindings;
    @Resource private DriveFolderApi driveFolders;
    @Resource private DataObjectApi objects;
    @Resource private RecordQueryAccess records;
    @Resource private RecordFolderResolver resolver;
    @Resource private RecordFolderAutoCreator autoCreator;
    @Resource private ObjectMapper json;
    @Resource private PlatformTransactionManager manager;

    private TransactionTemplate transaction;

    @PostConstruct
    void initialize() {
        transaction = new TransactionTemplate(manager);
    }

    // ========== 读取 ==========

    public List<RecordFolders.Source> list(String objectId, long actor) {
        List<RecordFolderSourceDO> rows = sources.selectByObject(requireObjectId(objectId));
        if (rows.isEmpty()) return List.of();
        DataCenter.Definition definition = published(objectId);
        List<RecordFolders.Source> result = new ArrayList<>(rows.size());
        for (RecordFolderSourceDO row : rows) result.add(view(row, definition, actor));
        return result;
    }

    public List<RecordFolders.Candidate> candidates(String objectId, long actor) {
        DataCenter.Definition definition = requirePublished(objectId);
        List<RecordFolders.Candidate> result = new ArrayList<>();
        for (DataCenter.Relation relation : definition.relations()) {
            // 明细里的关系不列
            if (relation.sourceDetailId() != null) continue;
            FieldDefinition field = field(definition, relation.fieldId());
            String name = field != null ? field.name() : relation.name();
            DataCenter.Definition target = published(relation.targetObjectId());
            String targetName = target == null ? "" : target.objectName();
            if (RelationTypeEnum.MANY_TO_MANY.matches(relation.kind())) {
                result.add(
                        new RecordFolders.Candidate(
                                relation.fieldId(),
                                name,
                                relation.targetObjectId(),
                                targetName,
                                List.of(),
                                "多对多关联暂不支持"));
                continue;
            }
            if (relation.fieldId() == null) continue;
            List<RecordFolders.CandidateSource> offered = new ArrayList<>();
            for (RecordFolderSourceDO row : sources.selectByObject(relation.targetObjectId()))
                offered.add(
                        new RecordFolders.CandidateSource(
                                row.getId().toString(), displayLabel(row, target)));
            result.add(
                    new RecordFolders.Candidate(
                            relation.fieldId(),
                            name,
                            relation.targetObjectId(),
                            targetName,
                            offered,
                            offered.isEmpty() ? "对方还没有配置文件夹" : null));
        }
        return result;
    }

    public List<RecordFolders.NameField> nameFields(String objectId, long actor) {
        DataCenter.Definition definition = requirePublished(objectId);
        List<RecordFolders.NameField> result = new ArrayList<>();
        for (FieldDefinition field : definition.fields())
            if (nameable(definition, field))
                result.add(
                        new RecordFolders.NameField(
                                field.id(), field.name(), nameType(definition, field)));
        return result;
    }

    /** 页签名称：配了用配的；否则指定文件夹当前的名字，或关联字段的名字 */
    public String displayLabel(RecordFolderSourceDO row, DataCenter.Definition definition) {
        if (row.getLabel() != null && !row.getLabel().isBlank()) return row.getLabel();
        if (RecordFolderKindEnum.FOLDER.matches(row.getKind())) {
            DriveFolderInfo info = driveFolders.describe(row.getEntryId());
            return info == null ? "" : info.name();
        }
        FieldDefinition field =
                definition == null ? null : field(definition, row.getRelationFieldId());
        return field == null ? "" : field.name();
    }

    private RecordFolders.Source view(
            RecordFolderSourceDO row, DataCenter.Definition definition, long actor) {
        boolean subfolder = RecordFolderPlacementEnum.RECORD_SUBFOLDER.matches(row.getPlacement());
        String folderPath = null;
        String relationName = null;
        String targetObjectId = null;
        String targetObjectName = null;
        String targetLabel = null;
        String problem = null;
        if (RecordFolderKindEnum.FOLDER.matches(row.getKind())) {
            DriveFolderInfo info = driveFolders.describe(row.getEntryId());
            if (info == null) problem = RecordFolderResolver.FOLDER_DELETED;
            else if (!info.folder()
                    || info.managed()
                    || !Objects.equals(info.spaceId(), row.getSpaceId()))
                problem = RecordFolderResolver.CONFIG_INVALID;
            else if (info.trashed()) problem = RecordFolderResolver.FOLDER_TRASHED;
            else if (!info.spaceEnabled()) problem = RecordFolderResolver.SPACE_DISABLED;
            if (info != null)
                folderPath =
                        driveFolders.roleOf(info.spaceId(), info.id(), actor) != null
                                ? driveFolders.displayPath(info.id())
                                : NO_DRIVE_VIEW;
        } else {
            FieldDefinition field =
                    definition == null ? null : field(definition, row.getRelationFieldId());
            DataCenter.Relation relation =
                    RecordFolders.singleRelation(definition, row.getRelationFieldId());
            RecordFolderSourceDO target = sources.selectActive(row.getTargetSourceId());
            if (field != null) relationName = field.name();
            if (relation != null) targetObjectId = relation.targetObjectId();
            else if (target != null) targetObjectId = target.getObjectId();
            DataCenter.Definition targetDefinition =
                    targetObjectId == null ? null : published(targetObjectId);
            if (targetDefinition != null) targetObjectName = targetDefinition.objectName();
            if (target != null) targetLabel = displayLabel(target, targetDefinition);
            if (field == null
                    || relation == null
                    || target == null
                    || !target.getObjectId().equals(relation.targetObjectId()))
                problem = RecordFolderResolver.CONFIG_INVALID;
        }
        return new RecordFolders.Source(
                row.getId().toString(),
                row.getObjectId(),
                row.getKind(),
                row.getPlacement(),
                row.getLabel(),
                row.getSpaceId(),
                row.getEntryId(),
                row.getRelationFieldId() == null || row.getRelationFieldId().isEmpty()
                        ? null
                        : row.getRelationFieldId(),
                row.getTargetSourceId() == null ? null : row.getTargetSourceId().toString(),
                subfolder
                        ? row.getCreateMode()
                        : RecordFolderCreateModeEnum.ON_FIRST_WRITE.getCode(),
                subfolder ? template(row.getNameTemplate()) : null,
                displayLabel(row, definition),
                folderPath,
                relationName,
                targetObjectId,
                targetObjectName,
                targetLabel,
                problem);
    }

    // ========== 保存 ==========

    /** 整份替换：带 id 的更新，不带的新增，库里有而入参没有的逻辑删除；顺序 = 入参下标。同一事务，任一条不过整份拒绝。 */
    public List<RecordFolders.Source> save(RecordFolders.SaveConfig command, long actor) {
        if (command == null) throw invalid(INVALID);
        String objectId = command.objectId();
        DataCenter.Definition definition = requirePublished(objectId);
        List<RecordFolders.SourceInput> inputs =
                command.sources() == null ? List.of() : command.sources();
        if (inputs.size() > MAX_SOURCES) throw invalid("一个对象最多配置 " + MAX_SOURCES + " 个文件夹");
        transaction.executeWithoutResult(
                status -> {
                    Map<Long, RecordFolderSourceDO> existing = new LinkedHashMap<>();
                    for (RecordFolderSourceDO row : sources.selectByObject(objectId))
                        existing.put(row.getId(), row);
                    List<RecordFolderSourceDO> next =
                            normalize(objectId, definition, inputs, existing, actor);
                    Set<Long> kept = new HashSet<>();
                    for (RecordFolderSourceDO row : next)
                        if (row.getId() != null) kept.add(row.getId());
                    List<Long> removed =
                            existing.keySet().stream().filter(id -> !kept.contains(id)).toList();
                    requireChains(objectId, next, removed);
                    requireUnreferenced(objectId, removed);
                    String operator = Long.toString(actor);
                    if (!removed.isEmpty()) sources.deleteSources(removed, operator);
                    for (RecordFolderSourceDO row : next) {
                        if (row.getId() != null) {
                            sources.updateSource(row, operator);
                        } else {
                            row.setCreator(operator);
                            row.setUpdater(operator);
                            sources.insert(row);
                        }
                    }
                });
        autoCreator.forget(objectId);
        return list(objectId, actor);
    }

    /** 逐条校验并换成要落库的行（C3–C9、C12–C14）；顺序 = 入参下标 */
    private List<RecordFolderSourceDO> normalize(
            String objectId,
            DataCenter.Definition definition,
            List<RecordFolders.SourceInput> inputs,
            Map<Long, RecordFolderSourceDO> existing,
            long actor) {
        List<RecordFolderSourceDO> result = new ArrayList<>(inputs.size());
        Set<Long> seenIds = new HashSet<>();
        Set<String> labels = new HashSet<>();
        Set<String> shapes = new HashSet<>();
        for (int index = 0; index < inputs.size(); index++) {
            RecordFolders.SourceInput input = inputs.get(index);
            if (input == null
                    || !RecordFolderKindEnum.containsCode(input.kind())
                    || !RecordFolderPlacementEnum.containsCode(input.placement()))
                throw invalid(INVALID);
            RecordFolderSourceDO before = null;
            if (input.id() != null && !input.id().isBlank()) {
                Long id = number(input.id());
                before = id == null ? null : existing.get(id);
                if (before == null || !seenIds.add(id)) throw invalid(INVALID);
            }
            String label = input.label() == null ? "" : input.label().trim();
            if (label.length() > 20) throw invalid("页签名称不能超过 20 个字");
            if (!label.isEmpty() && !labels.add(label)) throw invalid("页签名称「" + label + "」重复");

            RecordFolderSourceDO row = new RecordFolderSourceDO();
            row.setId(before == null ? null : before.getId());
            row.setObjectId(objectId);
            row.setSortNo(index);
            row.setLabel(label);
            row.setKind(input.kind());
            row.setPlacement(input.placement());
            if (RecordFolderKindEnum.FOLDER.matches(input.kind())) {
                appoint(row, input, before, actor);
                if (!shapes.add("F|" + row.getEntryId() + "|" + row.getPlacement()))
                    throw invalid("同一个文件夹同一种放法不能添加两次");
            } else {
                relate(row, input, definition, objectId, inputs);
                if (!shapes.add(
                        "R|"
                                + row.getRelationFieldId()
                                + "|"
                                + row.getTargetSourceId()
                                + "|"
                                + row.getPlacement())) throw invalid("同一个文件夹同一种放法不能添加两次");
            }
            if (RecordFolderPlacementEnum.DIRECT.matches(input.placement())) {
                // 共用一个文件夹时没有子文件夹：建立时机与命名模板不校验，一律归一
                row.setCreateMode(RecordFolderCreateModeEnum.ON_FIRST_WRITE.getCode());
                row.setNameTemplate("");
            } else {
                row.setCreateMode(createMode(input.createMode()));
                row.setNameTemplate(nameTemplate(input.nameTemplate(), definition));
            }
            result.add(row);
        }
        return result;
    }

    /** C5、C6：指定网盘里的文件夹 */
    private void appoint(
            RecordFolderSourceDO row,
            RecordFolders.SourceInput input,
            RecordFolderSourceDO before,
            long actor) {
        if (input.spaceId() == null || input.entryId() == null) throw invalid(INVALID);
        DriveFolderInfo info = driveFolders.describe(input.entryId());
        if (info == null
                || !info.folder()
                || info.managed()
                || info.trashed()
                || !info.spaceEnabled()
                || !Objects.equals(info.spaceId(), input.spaceId())) throw invalid("所选文件夹不存在或已不可用");
        if (!"BIZ".equals(info.spaceType()) && !"TEAM".equals(info.spaceType()))
            throw invalid("不能关联个人空间里的文件夹");
        // 已存在且指向没变的来源不重复校验：别人配的不该因为我没有那个文件夹的权限就存不了
        boolean unchanged =
                before != null
                        && RecordFolderKindEnum.FOLDER.matches(before.getKind())
                        && Objects.equals(before.getSpaceId(), input.spaceId())
                        && Objects.equals(before.getEntryId(), input.entryId());
        if (!unchanged
                && !"MANAGER".equals(driveFolders.roleOf(input.spaceId(), input.entryId(), actor)))
            throw invalid("你在网盘里没有管理这个文件夹的权限");
        row.setSpaceId(input.spaceId());
        row.setEntryId(input.entryId());
        row.setRelationFieldId("");
        row.setTargetSourceId(null);
    }

    /** C8、C9：用关联记录的文件夹 */
    private void relate(
            RecordFolderSourceDO row,
            RecordFolders.SourceInput input,
            DataCenter.Definition definition,
            String objectId,
            List<RecordFolders.SourceInput> inputs) {
        DataCenter.Relation relation =
                RecordFolders.singleRelation(definition, input.relationFieldId());
        if (relation == null
                || field(definition, input.relationFieldId()) == null
                || !Set.of(
                                RelationTypeEnum.REFERENCE.getCode(),
                                RelationTypeEnum.MASTER_DETAIL.getCode(),
                                RelationTypeEnum.ONE_TO_ONE.getCode())
                        .contains(relation.kind())) throw invalid("关联字段不存在，或不是主表上的单值关联");
        Long targetId = number(input.targetSourceId());
        RecordFolderSourceDO target = targetId == null ? null : sources.selectActive(targetId);
        // 对方就是本对象（自关联）时，对方的来源以本次入参为准：这次要删掉的不算存在
        boolean removedNow =
                target != null
                        && target.getObjectId().equals(objectId)
                        && inputs.stream()
                                .noneMatch(
                                        item ->
                                                item != null
                                                        && targetId.toString().equals(item.id()));
        if (target == null || removedNow || !target.getObjectId().equals(relation.targetObjectId()))
            throw invalid("对方对象没有这个文件夹");
        row.setSpaceId(null);
        row.setEntryId(null);
        row.setRelationFieldId(input.relationFieldId());
        row.setTargetSourceId(targetId);
    }

    /** C12 */
    private static String createMode(String code) {
        if (code == null || code.isBlank())
            return RecordFolderCreateModeEnum.ON_FIRST_WRITE.getCode();
        if (!RecordFolderCreateModeEnum.containsCode(code)) throw invalid(INVALID);
        return code;
    }

    /** C13、C14：返回落库的 JSON 文本；没有模板（用记录名称）时为空串 */
    private String nameTemplate(
            RecordFolders.NameTemplate template, DataCenter.Definition definition) {
        if (template == null) return "";
        List<RecordFolders.NamePart> parts =
                template.parts() == null ? List.of() : template.parts();
        if (parts.size() > 5) throw invalid("文件夹名称最多由 5 段组成");
        String separator = template.separator() == null ? "" : template.separator();
        if (!SEPARATORS.contains(separator)) throw invalid("文件夹名称的分隔符不支持");
        List<RecordFolders.NamePart> normalized = new ArrayList<>(parts.size());
        Set<String> fields = new HashSet<>();
        for (RecordFolders.NamePart part : parts) {
            if (part == null || !RecordFolderNamePartEnum.containsCode(part.kind()))
                throw invalid(INVALID);
            if (RecordFolderNamePartEnum.TEXT.matches(part.kind())) {
                String text = part.text() == null ? "" : part.text().trim();
                if (text.isEmpty()
                        || text.length() > 20
                        || text.chars()
                                .anyMatch(
                                        value ->
                                                value == '/'
                                                        || value == '\\'
                                                        || value == '\r'
                                                        || value == '\n'
                                                        || value == '\t'))
                    throw invalid("固定文字要在 1 到 20 个字之间，且不能包含 / 或 \\");
                normalized.add(
                        new RecordFolders.NamePart(
                                RecordFolderNamePartEnum.TEXT.getCode(), null, text));
                continue;
            }
            FieldDefinition field = field(definition, part.fieldId());
            if (field == null || !nameable(definition, field))
                throw invalid(
                        "字段「" + (field != null ? field.name() : part.fieldId()) + "」不能用来给文件夹命名");
            if (!fields.add(field.id())) throw invalid("文件夹名称里的字段不能重复");
            normalized.add(
                    new RecordFolders.NamePart(
                            RecordFolderNamePartEnum.FIELD.getCode(), field.id(), null));
        }
        if (fields.isEmpty()) throw invalid("文件夹名称里至少要有一个字段");
        try {
            String text =
                    json.writeValueAsString(new RecordFolders.NameTemplate(separator, normalized));
            if (text.length() > 2000) throw invalid(INVALID);
            return text;
        } catch (com.fasterxml.jackson.core.JsonProcessingException unreadable) {
            throw invalid(INVALID);
        }
    }

    private RecordFolders.NameTemplate template(String stored) {
        if (stored == null || stored.isBlank()) return null;
        try {
            return json.readValue(stored, RecordFolders.NameTemplate.class);
        } catch (java.io.IOException unreadable) {
            return null;
        }
    }

    /** C10：以「本次入参 + 别的对象库里现状」模拟，从每个「用关联记录的文件夹」来源沿对方的来源往下走，经过的这类来源数不超过上限，且不回到走过的来源。 */
    private void requireChains(
            String objectId, List<RecordFolderSourceDO> next, List<Long> removed) {
        Map<Long, RecordFolderSourceDO> mine = new HashMap<>();
        for (RecordFolderSourceDO row : next) if (row.getId() != null) mine.put(row.getId(), row);
        for (RecordFolderSourceDO start : next) {
            if (!RecordFolderKindEnum.RELATION.matches(start.getKind())) continue;
            Set<Long> visited = new HashSet<>();
            if (start.getId() != null) visited.add(start.getId());
            int relations = 1;
            RecordFolderSourceDO current = start;
            while (RecordFolderKindEnum.RELATION.matches(current.getKind())) {
                Long targetId = current.getTargetSourceId();
                if (!visited.add(targetId)) throw invalid("文件夹关联形成了循环");
                RecordFolderSourceDO target = mine.get(targetId);
                if (target == null && !removed.contains(targetId))
                    target = sources.selectActive(targetId);
                // 链上更深处的来源已不存在：运行时按「配置已失效」处理，这里不再往下走
                if (target == null) break;
                if (RecordFolderKindEnum.RELATION.matches(target.getKind())
                        && ++relations > RecordFolderResolver.MAX_RELATION_DEPTH)
                    throw invalid("文件夹关联最多隔 " + RecordFolderResolver.MAX_RELATION_DEPTH + " 层");
                current = target;
            }
        }
    }

    /** C11：要删除的来源不能被别的未删除来源引用 */
    private void requireUnreferenced(String objectId, List<Long> removed) {
        if (removed.isEmpty()) return;
        for (RecordFolderSourceDO referrer : sources.selectReferrers(removed)) {
            // 本对象自己的来源以本次入参为准：还引用着被删来源的那条已在逐条校验里按「对方对象没有这个文件夹」拒绝
            if (referrer.getObjectId().equals(objectId)) continue;
            DataCenter.Definition owner = published(referrer.getObjectId());
            throw invalid(
                    "「"
                            + (owner == null ? referrer.getObjectId() : owner.objectName())
                            + "」的「"
                            + displayLabel(referrer, owner)
                            + "」还在用这个文件夹，不能删除");
        }
    }

    // ========== 补建 ==========

    /** 为已有记录补建文件夹：处理一页，前端按返回的游标一页一页驱动。与建立时机无关，两种时机的来源都可以补建。 */
    public RecordFolders.BackfillResult backfill(RecordFolders.Backfill command, long actor) {
        if (command == null) throw invalid(INVALID);
        requirePublished(command.objectId());
        Long id = number(command.sourceId());
        RecordFolderSourceDO source = id == null ? null : sources.selectActive(id);
        if (source == null || !source.getObjectId().equals(command.objectId()))
            throw invalid(INVALID);
        if (!RecordFolderPlacementEnum.RECORD_SUBFOLDER.matches(source.getPlacement()))
            throw invalid("这个文件夹不需要补建");
        int limit =
                command.limit() == null || command.limit() <= 0
                        ? BACKFILL_LIMIT
                        : Math.min(command.limit(), BACKFILL_LIMIT);
        return page(source, command.cursor(), limit, actor);
    }

    /**
     * 处理一页记录：已有的记数，还没建的建出来，没选关联或文件夹当前不可用的跳过，出错的记入失败清单并继续——一条失败不中断这一页。
     *
     * <p>幂等：重复调用、与后台自动建并发都安全（建之前锁来源行并重新解析）。
     */
    RecordFolders.BackfillResult page(
            RecordFolderSourceDO source, String cursor, int limit, long actor) {
        String objectId = source.getObjectId();
        List<Row> scanned =
                records.scanStored(
                        objectId, cursor == null || cursor.isBlank() ? null : cursor, limit, actor);
        boolean done = scanned.size() <= limit;
        List<Row> rows = done ? scanned : scanned.subList(0, limit);
        // 指定文件夹的来源：先用一条查询取出这一页里已有对应关系的记录，这些不逐条解析
        Set<String> bound =
                rows.isEmpty() || !RecordFolderKindEnum.FOLDER.matches(source.getKind())
                        ? Set.of()
                        : new HashSet<>(
                                bindings.selectBoundRecordIds(
                                        objectId,
                                        source.getId(),
                                        0L,
                                        rows.stream().map(Row::id).toList()));
        int created = 0;
        int existing = 0;
        int skipped = 0;
        int failed = 0;
        List<RecordFolders.BackfillFailure> failures = new ArrayList<>();
        for (Row row : rows) {
            if (bound.contains(row.id())) {
                existing++;
                continue;
            }
            try {
                RecordFolderResolver.Target target =
                        resolver.resolve(source, objectId, row.id(), row, actor);
                if (target.ready()) {
                    existing++;
                } else if (!target.pending()) {
                    skipped++;
                } else if (resolver.ensure(source, objectId, row.id(), row, actor).ready()) {
                    created++;
                } else {
                    skipped++;
                }
            } catch (RuntimeException error) {
                failed++;
                boolean business = error instanceof ServiceException;
                if (!business)
                    log.warn(
                            "record folder backfill failed: object={} source={} record={}",
                            objectId,
                            source.getId(),
                            row.id(),
                            error);
                if (failures.size() < FAILURE_SAMPLES)
                    failures.add(
                            new RecordFolders.BackfillFailure(
                                    row.id(),
                                    business ? error.getMessage() : "建立这条记录的文件夹时出错，请稍后重试"));
            }
        }
        return new RecordFolders.BackfillResult(
                rows.isEmpty() ? cursor : rows.getLast().id(),
                done,
                rows.size(),
                created,
                existing,
                skipped,
                failed,
                failures);
    }

    // ========== 公共 ==========

    private static String requireObjectId(String objectId) {
        if (objectId == null || !objectId.matches("[1-9][0-9]{0,18}"))
            throw invalid("数据对象不存在或尚未发布");
        return objectId;
    }

    /** C1 */
    private DataCenter.Definition requirePublished(String objectId) {
        DataCenter.Definition definition = published(requireObjectId(objectId));
        if (definition == null) throw invalid("数据对象不存在或尚未发布");
        return definition;
    }

    /** 对象的当前发布版本；不存在、未发布或已停用返回 null */
    private DataCenter.Definition published(String objectId) {
        try {
            return objects.getPublished(objectId);
        } catch (ServiceException | IllegalArgumentException missing) {
            return null;
        }
    }

    private static Long number(String text) {
        if (text == null || !text.matches("[1-9][0-9]{0,18}")) return null;
        try {
            return Long.valueOf(text);
        } catch (NumberFormatException overflow) {
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

    /** C14：主表上未停用、类型可命名的字段；背后是单值关联的字段可用，多对多不在主表字段里 */
    private static boolean nameable(DataCenter.Definition definition, FieldDefinition field) {
        DataCenter.FieldOptions options = definition.fieldOptions().get(field.id());
        if (options != null && MemberStateEnum.INACTIVE.matches(options.state())) return false;
        if (RecordFolders.singleRelation(definition, field.id()) != null) return true;
        if (!FieldTypeEnum.containsCode(field.type())) return false;
        FieldTypeEnum type = FieldTypeEnum.fromCode(field.type());
        // 类型是 REFERENCE 却找不到单值关联（关联已删或是多对多）：不可用
        return type != FieldTypeEnum.REFERENCE && NAMEABLE.contains(type);
    }

    /** 给界面的字段类型：背后是单值关联的字段一律报 REFERENCE（关联列在定义里可能是整数或文本） */
    private static String nameType(DataCenter.Definition definition, FieldDefinition field) {
        return RecordFolders.singleRelation(definition, field.id()) != null
                ? FieldTypeEnum.REFERENCE.getCode()
                : field.type();
    }
}

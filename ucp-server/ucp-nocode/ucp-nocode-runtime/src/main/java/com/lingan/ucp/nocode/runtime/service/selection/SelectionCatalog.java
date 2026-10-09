package com.lingan.ucp.nocode.runtime.service.selection;

import static com.lingan.ucp.nocode.api.NocodeErrorCodes.invalid;

import com.lingan.ucp.framework.common.enums.CommonStatusEnum;
import com.lingan.ucp.module.bpm.api.definition.BpmUserGroupApi;
import com.lingan.ucp.module.system.api.dict.DictDataApi;
import com.lingan.ucp.module.system.api.organization.OrganizationApi;
import com.lingan.ucp.module.system.enums.organization.OrganizationStatusEnum;
import com.lingan.ucp.module.system.service.dept.DeptService;
import com.lingan.ucp.module.system.service.dept.PostService;
import com.lingan.ucp.module.system.service.organization.OrganizationService;
import com.lingan.ucp.module.system.service.user.AdminUserService;
import com.lingan.ucp.nocode.api.*;
import com.lingan.ucp.nocode.api.SelectionFields.*;
import com.lingan.ucp.nocode.enums.*;
import com.lingan.ucp.nocode.runtime.service.record.RuntimeSchema;

import jakarta.annotation.Resource;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.*;

/** 复用系统服务提供选择候选、范围过滤及回显；应用身份和字段权限由调用方先校验。 */
@Service
public class SelectionCatalog implements SelectionTargetValidator {
    @Resource private ObjectProvider<OrganizationService> organizations;
    @Resource private com.fasterxml.jackson.databind.ObjectMapper json;
    @Resource private ObjectProvider<OrganizationApi> organizationApiProvider;
    @Resource private ObjectProvider<DeptService> departments;
    @Resource private ObjectProvider<PostService> posts;
    @Resource private ObjectProvider<AdminUserService> users;
    @Resource private ObjectProvider<DictDataApi> dictionaries;
    @Resource private ObjectProvider<BpmUserGroupApi> groups;
    @Resource private ObjectProvider<DataObjectApi> objects;

    @Resource
    private ObjectProvider<com.lingan.ucp.nocode.application.service.application.ApplicationService>
            applications;

    @Resource
    private ObjectProvider<com.lingan.ucp.nocode.application.service.sharing.ImpliedObjects>
            impliedProvider;

    @Resource
    private ObjectProvider<com.lingan.ucp.nocode.application.service.sharing.ObjectSharingService>
            sharing;

    private final ThreadLocal<ApplicationScope> applicationScope = new ThreadLocal<>();

    /** 「挑取值」的候选与状态；只读来源对象在应用中固定版本的字段选项，一行业务记录都不读。 */
    public record ObjectFieldOptions(String state, String message, List<Option> options) {}

    /**
     * 挑取值解析来源对象所需的上下文。preview 非空时来源取服务端核验过的草稿引用；objectDesign 为真时是数据对象设计器的唯一例外（见 inObjectDesign）。
     */
    public record ApplicationScope(
            String applicationId,
            Map<String, DataCenter.Definition> preview,
            boolean objectDesign) {}

    /** 在已知应用的入口内执行：挑取值的来源对象按该应用固定的对象版本解析。可嵌套，结束后恢复外层。 */
    public <T> T inApplication(
            String applicationId,
            Map<String, DataCenter.Definition> preview,
            java.util.function.Supplier<T> action) {
        var previous = applicationScope.get();
        return scoped(new ApplicationScope(applicationId, preview, false), action);
    }

    private <T> T scoped(ApplicationScope scope, java.util.function.Supplier<T> action) {
        var previous = applicationScope.get();
        applicationScope.set(scope);
        try {
            return action.get();
        } finally {
            if (previous == null) applicationScope.remove();
            else applicationScope.set(previous);
        }
    }

    /** 应用设计器预览：来源对象按正在设计的应用草稿里固定的 versionNo/checksum 解析（与 D6「只读固定版本」同口径），草稿引用经 normalize 核验。 */
    public <T> T inApplicationDraft(String applicationId, java.util.function.Supplier<T> action) {
        var service = applications.getObject();
        var refs =
                service.normalize(
                                new ApplicationCenter.Definition(
                                        service.get(applicationId).draft().objects(), List.of()))
                        .objects();
        com.lingan.ucp.nocode.application.service.sharing.ImpliedObjects.Definitions definitions =
                new com.lingan.ucp.nocode.application.service.sharing.ImpliedObjects.Definitions();
        for (var ref : refs)
            definitions.put(
                    ref.objectId(),
                    objects.getObject().getVersion(ref.objectId(), ref.versionNo()).definition());
        // 草稿没引用、但因关联而隐式可读的来源对象也能挑取值。
        impliedProvider.getObject().complete(definitions);
        return inApplication(applicationId, definitions, action);
    }

    /**
     * 唯一的例外：数据对象设计器（字段选项预览、对象保存与发布时的选择值校验）没有应用，预览的是对象自身，因此挑取值的来源对象按其当前发布版解析。
     * 运行期、应用设计器与报表一律按应用固定版本，不得使用本方法。已处于其它上下文时不覆盖。
     */
    public <T> T inObjectDesign(java.util.function.Supplier<T> action) {
        if (applicationScope.get() != null) return action.get();
        return scoped(new ApplicationScope(null, null, true), action);
    }

    public void inApplication(String applicationId, Runnable action) {
        inApplication(
                applicationId,
                null,
                () -> {
                    action.run();
                    return null;
                });
    }

    /** 结构化判断来源对象：不在应用固定对象集里 → 未加入应用；在，但按固定版本取不到或已停用 → 已不存在。没有应用上下文时同样 fail-closed，不读最新发布版。 */
    private Object pinnedSource(String objectId) {
        var scope = applicationScope.get();
        if (scope == null)
            return new ObjectFieldOptions(
                    FieldRuleStateEnum.SOURCE_TABLE_MISSING.getCode(),
                    "「挑取值」缺少应用上下文，无法确定来源对象的固定版本，不给选项",
                    List.of());
        // 对象数据维护入口不带应用：来源对象按其当前发布版解析（与对象设计器同口径），不报空指针。
        boolean maintenance =
                scope.applicationId() == null
                        && scope.preview() == null
                        && com.lingan.ucp.nocode.runtime.service.maintenance.ObjectMaintenanceScope
                                .active(null);
        if (scope.objectDesign() || maintenance) {
            try {
                return objects.getObject().getPublished(objectId);
            } catch (com.lingan.ucp.framework.common.exception.ServiceException e) {
                return missing("「挑取值」的来源对象未发布或已停用，不给选项");
            }
        }
        if (scope.preview() != null) {
            var d = scope.preview().get(objectId);
            return d != null ? d : missing("「挑取值」的来源对象未加入应用，不给选项");
        }
        if (scope.applicationId() == null)
            return new ObjectFieldOptions(
                    FieldRuleStateEnum.SOURCE_TABLE_MISSING.getCode(),
                    "「挑取值」缺少应用上下文，无法确定来源对象的固定版本，不给选项",
                    List.of());
        var ref =
                applications
                        .getObject()
                        .published(scope.applicationId())
                        .definition()
                        .objects()
                        .stream()
                        .filter(r -> r.objectId().equals(objectId))
                        .findFirst()
                        .orElse(null);
        if (ref == null) {
            // 应用没有引用、但因关联而隐式可读的来源对象：没有固定版本，用最新发布版；数据管理员撤销了授权则读不了。
            com.lingan.ucp.nocode.application.service.sharing.ImpliedObjects implied =
                    impliedProvider.getObject();
            DataCenter.Definition latest =
                    implied.implied(scope.applicationId(), objectId)
                            ? implied.latest(objectId)
                            : null;
            if (latest == null) return missing("「挑取值」的来源对象未加入应用，不给选项");
            if (sharing.getObject().ceiling(scope.applicationId(), latest) == null)
                return missing("「挑取值」的来源对象对本应用的授权已撤销，不给选项");
            return latest;
        }
        try {
            var version = objects.getObject().getVersion(objectId, ref.versionNo());
            if (!version.checksum().equals(ref.checksum()))
                return missing("「挑取值」的来源对象已不存在或已停用，不给选项");
            return version.definition();
        } catch (com.lingan.ucp.framework.common.exception.ServiceException e) {
            return missing("「挑取值」的来源对象已不存在或已停用，不给选项");
        }
    }

    private static ObjectFieldOptions missing(String message) {
        return new ObjectFieldOptions(
                FieldRuleStateEnum.SOURCE_TABLE_MISSING.getCode(), message, List.of());
    }

    public List<Option> options(FieldDefinition field, DataCenter.FieldOptions options) {
        SelectionFields.Source source = SelectionFields.source(field, options);
        if (source == null) throw invalid("字段未配置选择来源");
        SelectionSourceEnum kind = SelectionSourceEnum.fromCode(source.kind());
        if (kind == SelectionSourceEnum.LOCAL_OPTIONS)
            return options.options().stream()
                    .map(
                            o ->
                                    new Option(
                                            o.code(),
                                            o.label(),
                                            o.code(),
                                            null,
                                            o.label(),
                                            Boolean.TRUE.equals(o.disabled()),
                                            false))
                    .toList();
        if (kind == SelectionSourceEnum.OBJECT_FIELD_OPTIONS)
            return objectFieldOptions(source).options();
        if (kind == SelectionSourceEnum.SYSTEM_DICTIONARY)
            return dictionaries.getObject().getDictDataList(source.dictionaryType()).stream()
                    .map(
                            o ->
                                    new Option(
                                            o.getValue(),
                                            o.getLabel(),
                                            o.getValue(),
                                            null,
                                            o.getLabel(),
                                            !enabled(o.getStatus()),
                                            false))
                    .toList();
        if (kind != SelectionSourceEnum.DIRECTORY) throw invalid("业务对象来源必须经过对象关系查询");
        List<Option> all = new ArrayList<>();
        switch (FieldTypeEnum.fromCode(source.directory())) {
            case ORGANIZATION -> {
                List<
                                com.lingan.ucp.module.system.controller.admin.organization.vo
                                        .OrganizationTreeRespVO>
                        tree = organizations.getObject().getCurrentTenantTree(null, null);
                flattenOrganizations(tree, all, source, "");
            }
            case DEPARTMENT -> {
                List<com.lingan.ucp.module.system.dal.dataobject.dept.DeptDO> list =
                        departments
                                .getObject()
                                .getDeptList(
                                        new com.lingan.ucp.module.system.controller.admin.dept.vo
                                                .dept.DeptListReqVO());
                for (com.lingan.ucp.module.system.dal.dataobject.dept.DeptDO d : list)
                    all.add(
                            new Option(
                                    d.getId().toString(),
                                    d.getName(),
                                    d.getDeptCode(),
                                    Objects.toString(d.getParentId(), null),
                                    d.getName(),
                                    !enabled(d.getStatus()),
                                    false));
                all = paths(all);
            }
            case POST -> {
                // 单参数接口按 ID 集合查询，空集合表示无记录；目录候选复用支持可选条件的接口。
                for (com.lingan.ucp.module.system.dal.dataobject.dept.PostDO p :
                        posts.getObject().getPostList(null, null))
                    all.add(
                            new Option(
                                    p.getId().toString(),
                                    p.getName(),
                                    p.getCode(),
                                    null,
                                    p.getName(),
                                    !enabled(p.getStatus()),
                                    false));
            }
            case USER_GROUP -> {
                for (BpmUserGroupApi.SelectionGroup g : groups.getObject().getSelectionGroups())
                    all.add(
                            new Option(
                                    g.id(),
                                    g.name(),
                                    g.id(),
                                    null,
                                    g.name(),
                                    !enabled(g.status()),
                                    false));
            }
            case USER -> {
                // 系统服务保留自身数据权限；只输出选择所需身份与名称。
                for (com.lingan.ucp.module.system.dal.dataobject.user.AdminUserDO u :
                        users.getObject().getUserListByStatus(null))
                    all.add(
                            new Option(
                                    u.getId().toString(),
                                    u.getNickname(),
                                    u.getUsername(),
                                    null,
                                    u.getNickname() + "（" + u.getUsername() + "）",
                                    !enabled(u.getStatus()),
                                    false));
            }
            default -> throw invalid("不支持的系统目录");
        }
        if (all.size() > 20000) throw invalid("目录超过单次处理范围，请缩小目录范围");
        return restrict(all, source);
    }

    /**
     * 来源对象按当前应用固定的对象版本解析（见 inApplication），不读最新发布版。候选 = 来源字段实际生效的选项集：自定义选项取 options[]，公共字典取字典项（复用
     * options 的字典取项路径），其它来源视为没有选项。同一编码重复时收敛成一项，取第一条；停用项标 disabled。生效选项集为空时返回
     * SOURCE_FIELD_HAS_NO_OPTIONS，不以空下拉冒充「没有候选」。全程不读业务行。
     */
    public ObjectFieldOptions objectFieldOptions(Source source) {
        if (source.sourceObjectId() == null || source.sourceFieldId() == null)
            return new ObjectFieldOptions(
                    FieldRuleStateEnum.INCOMPLETE_CONFIG.getCode(),
                    "「挑取值」还没选来源对象或来源字段，不给选项",
                    List.of());
        var pinned = pinnedSource(source.sourceObjectId());
        if (pinned instanceof ObjectFieldOptions failed) return failed;
        var d = (DataCenter.Definition) pinned;
        FieldDefinition field = null;
        DataCenter.FieldOptions fieldOptions = null;
        for (var f : d.fields())
            if (f.id().equals(source.sourceFieldId())) {
                field = f;
                fieldOptions = d.fieldOptions().get(f.id());
            }
        for (var detail : d.details())
            for (var f : detail.fields())
                if (field == null && f.id().equals(source.sourceFieldId())) {
                    field = f;
                    fieldOptions = detail.fieldOptions().get(f.id());
                }
        if (field == null
                || fieldOptions != null && MemberStateEnum.INACTIVE.matches(fieldOptions.state()))
            return new ObjectFieldOptions(
                    FieldRuleStateEnum.SOURCE_FIELD_MISSING.getCode(),
                    "「挑取值」的来源字段在「" + d.objectName() + "」中已不存在或已停用，不给选项",
                    List.of());
        var effectiveOptions =
                fieldOptions == null ? DataCenter.FieldOptions.defaults() : fieldOptions;
        var effectiveSource = SelectionFields.source(field, effectiveOptions);
        boolean dictionary =
                effectiveSource != null
                        && SelectionSourceEnum.SYSTEM_DICTIONARY.matches(effectiveSource.kind());
        boolean local =
                effectiveSource != null
                        && SelectionSourceEnum.LOCAL_OPTIONS.matches(effectiveSource.kind())
                        && effectiveOptions.options() != null;
        List<Option> defined = dictionary || local ? options(field, effectiveOptions) : List.of();
        Map<String, Option> unique = new LinkedHashMap<>();
        for (var o : defined)
            if (o != null && o.value() != null && !o.value().isEmpty()) {
                String label = o.label() == null || o.label().isEmpty() ? o.value() : o.label();
                unique.putIfAbsent(
                        o.value(),
                        new Option(
                                o.value(),
                                label,
                                o.code() == null ? o.value() : o.code(),
                                null,
                                label,
                                o.disabled(),
                                false));
            }
        if (unique.isEmpty())
            return new ObjectFieldOptions(
                    FieldRuleStateEnum.SOURCE_FIELD_HAS_NO_OPTIONS.getCode(),
                    "「"
                            + field.name()
                            + "」一条选项都没有配置，不给空下拉冒充没有候选。怎么改：先到来源对象把它的选项配上；若要选的是记录本身，请改用对象关系（挑对象）",
                    List.of());
        return new ObjectFieldOptions(
                FieldRuleStateEnum.APPLIED.getCode(), null, List.copyOf(unique.values()));
    }

    private boolean userSource(FieldDefinition f, DataCenter.FieldOptions o) {
        SelectionFields.Source source = SelectionFields.source(f, o);
        return source != null
                && SelectionSourceEnum.DIRECTORY.matches(source.kind())
                && FieldTypeEnum.USER.matches(source.directory());
    }

    /** 大用户目录候选走底座分页，已选值走批量 ID 查询，避免逐行请求和全量加载。 */
    public com.lingan.ucp.framework.common.pojo.PageResult<Option> userPage(
            FieldDefinition f, DataCenter.FieldOptions o, String search, int page, int size) {
        if (!userSource(f, o)) return null;
        com.lingan.ucp.module.system.controller.admin.user.vo.user.UserPageReqVO query =
                new com.lingan.ucp.module.system.controller.admin.user.vo.user.UserPageReqVO();
        query.setKeyword(search);
        query.setStatus(CommonStatusEnum.ENABLE.getStatus());
        query.setPageNo(page);
        query.setPageSize(size);
        com.lingan.ucp.framework.common.pojo.PageResult<
                        com.lingan.ucp.module.system.dal.dataobject.user.AdminUserDO>
                result = users.getObject().getUserPage(query);
        return new com.lingan.ucp.framework.common.pojo.PageResult<>(
                result.getList().stream().map(this::userOption).toList(), result.getTotal());
    }

    private Option userOption(com.lingan.ucp.module.system.dal.dataobject.user.AdminUserDO u) {
        return new Option(
                u.getId().toString(),
                u.getNickname(),
                u.getUsername(),
                null,
                u.getNickname() + "（" + u.getUsername() + "）",
                !enabled(u.getStatus()),
                false);
    }

    public List<Option> selectedOptions(
            FieldDefinition f, DataCenter.FieldOptions o, Collection<String> ids) {
        if (ids.isEmpty()) return List.of();
        if (!userSource(f, o)) return options(f, o);
        List<Long> keys =
                ids.stream().filter(v -> v.matches("[1-9][0-9]{0,18}")).map(Long::valueOf).toList();
        return keys.isEmpty()
                ? List.of()
                : users.getObject().getUserList(keys).stream().map(this::userOption).toList();
    }

    public List<Option> scope(List<Option> base, Presentation p, Map<String, Object> formValues) {
        if (p == null) return base;
        List<Option> result = base;
        if (p.rootIds() != null && !p.rootIds().isEmpty())
            result =
                    restrict(
                            result,
                            new Source(
                                    SelectionSourceEnum.DIRECTORY.getCode(),
                                    null,
                                    null,
                                    p.rootIds(),
                                    p.includeDescendants(),
                                    null,
                                    null));
        if (p.linkFieldId() != null && p.linkTargetFieldId() == null) {
            List<String> roots = ids(formValues == null ? null : formValues.get(p.linkFieldId()));
            if (roots.isEmpty()) return List.of();
            result =
                    restrict(
                            result,
                            new Source(
                                    SelectionSourceEnum.DIRECTORY.getCode(),
                                    null,
                                    null,
                                    roots,
                                    p.includeDescendants(),
                                    null,
                                    null));
        }
        return result;
    }

    /** 导入显式 code: 前缀按来源唯一编码解析。普通字符串仍按稳定 ID／选项编码处理。 */
    public Object importValue(FieldDefinition f, DataCenter.FieldOptions o, Object raw) {
        if (SelectionFields.source(f, o) == null) return raw;
        List<String> result = new ArrayList<>();
        for (String input : ids(raw)) {
            if (!input.startsWith("code:")) {
                result.add(input);
                continue;
            }
            String code = input.substring(5);
            List<Option> candidates;
            if (userSource(f, o)) {
                com.lingan.ucp.framework.common.pojo.PageResult<SelectionFields.Option> page =
                        userPage(f, o, code, 1, 100);
                candidates = page.getList();
            } else candidates = options(f, o);
            List<SelectionFields.Option> matched =
                    candidates.stream()
                            .filter(v -> Objects.equals(v.code(), code) && !v.disabled())
                            .toList();
            if (matched.size() != 1) throw invalid("编码不存在、不唯一或不可选：" + f.name());
            result.add(matched.getFirst().value());
        }
        return SelectionFields.multiple(f) ? result : result.isEmpty() ? null : result.getFirst();
    }

    private void flattenOrganizations(
            List<
                            com.lingan.ucp.module.system.controller.admin.organization.vo
                                    .OrganizationTreeRespVO>
                    nodes,
            List<Option> all,
            Source source,
            String path) {
        if (nodes == null) return;
        for (com.lingan.ucp.module.system.controller.admin.organization.vo.OrganizationTreeRespVO
                node : nodes) {
            String full = path.isEmpty() ? node.getOrgName() : path + " / " + node.getOrgName();
            boolean typeAllowed =
                    source.organizationTypes() == null
                            || source.organizationTypes().isEmpty()
                            || source.organizationTypes().contains(node.getOrgType());
            all.add(
                    new Option(
                            node.getId(),
                            node.getOrgName(),
                            node.getOrgCode(),
                            node.getParentId(),
                            full,
                            !OrganizationStatusEnum.isEnable(node.getStatus()) || !typeAllowed,
                            false));
            flattenOrganizations(node.getChildren(), all, source, full);
        }
    }

    public static List<Option> restrict(List<Option> all, Source source) {
        if (source.rootIds() == null || source.rootIds().isEmpty()) return all;
        Set<String> allowed = new HashSet<>(source.rootIds());
        if (Boolean.TRUE.equals(source.includeDescendants())) {
            boolean changed;
            do {
                changed = false;
                for (SelectionFields.Option o : all)
                    if (allowed.contains(o.parentValue())) changed |= allowed.add(o.value());
            } while (changed);
        }
        return all.stream().filter(o -> allowed.contains(o.value())).toList();
    }

    private List<Option> paths(List<Option> all) {
        HashMap<String, SelectionFields.Option> map = new HashMap<String, Option>();
        all.forEach(o -> map.put(o.value(), o));
        return all.stream()
                .map(
                        o -> {
                            LinkedList<String> labels = new LinkedList<String>();
                            labels.add(o.label());
                            HashSet<String> seen = new HashSet<String>();
                            seen.add(o.value());
                            SelectionFields.Option parent = map.get(o.parentValue());
                            while (parent != null && seen.add(parent.value())) {
                                labels.addFirst(parent.label());
                                parent = map.get(parent.parentValue());
                            }
                            return new Option(
                                    o.value(),
                                    o.label(),
                                    o.code(),
                                    o.parentValue(),
                                    String.join(" / ", labels),
                                    o.disabled(),
                                    o.unavailable());
                        })
                .toList();
    }

    public static List<String> ids(Object value) {
        if (value == null) return List.of();
        if (value instanceof Collection<?> list) return list.stream().map(String::valueOf).toList();
        return List.of(value.toString());
    }

    /** 带入是本次新取值，不享受历史选择值的保留例外；读取和接收两侧都要仍可用。 */
    public void validateFillValue(
            FieldDefinition field, DataCenter.FieldOptions options, Object value) {
        if (value == null || value instanceof String text && text.isBlank()) return;
        if (value instanceof Collection<?> || value instanceof Map<?, ?>)
            throw invalid("关联带入单选值格式无效：" + field.name());
        boolean available =
                selectedOptions(field, options, List.of(value.toString())).stream()
                        .anyMatch(
                                option ->
                                        !option.disabled()
                                                && !option.unavailable()
                                                && Objects.equals(
                                                        option.value(), value.toString()));
        if (!available) throw invalid("关联带入选项不存在、已停用或超出允许范围：" + field.name());
    }

    public void validate(
            RuntimeSchema.Table table, Map<String, Object> payload, Map<String, Object> previous) {
        for (FieldDefinition f : table.fields()) {
            DataCenter.FieldOptions option =
                    table.options().getOrDefault(f.id(), DataCenter.FieldOptions.defaults());
            SelectionFields.Source source = SelectionFields.source(f, option);
            if (source == null || !payload.containsKey(table.column(f))) continue;
            Object value = payload.get(table.column(f));
            if (value == null || Objects.equals(ids(value), ids(previous.get(f.id())))) continue;
            // 旧专用目录字段沿用 RecordDirectoryValues 的校验；新来源在此附加范围约束。
            if (option.selection() == null
                    && SelectionFields.DIRECTORIES.contains(FieldTypeEnum.fromCode(f.type()))
                    && !FieldTypeEnum.ORGANIZATION.matches(f.type())) continue;
            if (SelectionSourceEnum.OBJECT_RELATION.matches(source.kind())) continue;
            if (SelectionFields.multiple(f) != (value instanceof Collection<?>))
                throw invalid("选择数量与字段定义不一致：" + f.name());
            List<String> selected = ids(value);
            if (selected.size() > 100 || new HashSet<>(selected).size() != selected.size())
                throw invalid("选择值重复或超过 100 项");
            List<String> added =
                    selected.stream()
                            .filter(id -> !ids(previous.get(f.id())).contains(id))
                            .toList();
            Set<String> allowed =
                    selectedOptions(f, option, added).stream()
                            .filter(o -> !o.disabled() && !o.unavailable())
                            .map(Option::value)
                            .collect(java.util.stream.Collectors.toSet());
            if (!allowed.containsAll(added)) throw invalid("选择值不存在、已停用或超出允许范围：" + f.name());
            if (SelectionSourceEnum.DIRECTORY.matches(source.kind())
                    && FieldTypeEnum.ORGANIZATION.matches(source.directory()))
                organizationApiProvider
                        .getObject()
                        .validateOrganizationList(added.stream().map(Long::valueOf).toList());
        }
    }

    public Object defaultValue(FieldDefinition f, DataCenter.FieldOptions o, long actor) {
        return defaultValue(f, o, null, Map.of(), actor);
    }

    public Object defaultValue(
            FieldDefinition f,
            DataCenter.FieldOptions o,
            Presentation p,
            Map<String, Object> formValues,
            long actor) {
        return evaluateDefault(f, o, p, formValues, actor).value();
    }

    public record DefaultValue(Object value, String warning) {}

    /** 默认值失效时保持为空，同时向新建界面说明原因；提交不能落回数据库旧默认值。 */
    public DefaultValue evaluateDefault(
            FieldDefinition f,
            DataCenter.FieldOptions o,
            Presentation p,
            Map<String, Object> formValues,
            long actor) {
        Object value =
                p != null && p.defaultValue() != null
                        ? p.defaultValue()
                        : configuredDefault(f, o, actor);
        SelectionFields.Source source = SelectionFields.source(f, o);
        if (source == null) return new DefaultValue(null, null);
        if (value == null)
            return new DefaultValue(
                    null,
                    SelectionDefaultEnum.CURRENT_USER_ORGANIZATION.matches(source.defaultMode())
                            ? "当前用户没有范围内的可用默认组织，请手动选择"
                            : null);
        List<String> allowed =
                scope(selectedOptions(f, o, ids(value)), p, formValues).stream()
                        .filter(v -> !v.disabled() && !v.unavailable())
                        .map(Option::value)
                        .toList();
        return allowed.containsAll(ids(value))
                ? new DefaultValue(value, null)
                : new DefaultValue(null, "默认值已失效或超出当前可选范围，请重新选择");
    }

    private Object configuredDefault(FieldDefinition f, DataCenter.FieldOptions o, long actor) {
        SelectionFields.Source source = SelectionFields.source(f, o);
        if (source == null) return null;
        if (o.selection() != null
                && FieldTypeEnum.ORGANIZATION.matches(source.directory())
                && SelectionDefaultEnum.NONE.matches(source.defaultMode())) return null;
        if (!SelectionDefaultEnum.CURRENT_USER_ORGANIZATION.matches(source.defaultMode())) {
            if (o.defaultValue() == null) return null;
            try {
                return SelectionFields.multiple(f)
                        ? json.readValue(o.defaultValue(), List.class)
                        : o.defaultValue();
            } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
                throw invalid("选择默认值格式无效：" + f.name());
            }
        }
        com.lingan.ucp.module.system.dal.dataobject.user.AdminUserDO user =
                users.getObject().getUser(actor);
        if (user == null || user.getOrgId() == null) return null;
        String id = user.getOrgId().toString();
        if (options(f, o).stream().noneMatch(v -> v.value().equals(id) && !v.disabled()))
            return null;
        return SelectionFields.multiple(f) ? List.of(id) : id;
    }

    /** 应用设计时（资源校验）：挑取值来源按本次引用的对象版本解析。 */
    @Override
    public <T> T inDefinitions(
            Map<String, DataCenter.Definition> definitions, java.util.function.Supplier<T> action) {
        var scope = applicationScope.get();
        return inApplication(scope == null ? null : scope.applicationId(), definitions, action);
    }

    /** 对象设计保存时的校验，没有应用：挑取值按对象设计器例外口径解析（见 inObjectDesign）。 */
    @Override
    public void validateDefinition(FieldDefinition f, DataCenter.FieldOptions o) {
        inObjectDesign(
                () -> {
                    checkDefinition(f, o);
                    return null;
                });
    }

    private void checkDefinition(FieldDefinition f, DataCenter.FieldOptions o) {
        var source = SelectionFields.source(f, o);
        if (source == null) return;
        if (source.rootIds() != null && !source.rootIds().isEmpty()) {
            SelectionFields.Source unrestricted =
                    new Source(
                            source.kind(),
                            source.directory(),
                            source.dictionaryType(),
                            List.of(),
                            false,
                            source.organizationTypes(),
                            source.defaultMode());
            List<SelectionFields.Option> all = options(f, o.withSelection(unrestricted));
            if (!all.stream().map(Option::value).toList().containsAll(source.rootIds()))
                throw invalid("可选范围中有不存在或不可见的目录记录：" + f.name());
        }
        if (o.defaultValue() != null) {
            try {
                Object value =
                        SelectionFields.multiple(f)
                                ? json.readValue(o.defaultValue(), List.class)
                                : o.defaultValue();
                validateTargets(f, o, ids(value));
            } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
                throw invalid("选择默认值格式无效：" + f.name());
            }
        }
    }

    @Override
    public void validatePresentation(FieldDefinition f, DataCenter.FieldOptions o, Presentation p) {
        if (p.defaultValue() == null) return;
        List<String> selected = ids(p.defaultValue());
        if (selected.size() > 100
                || new HashSet<>(selected).size() != selected.size()
                || Boolean.TRUE.equals(f.required()) && selected.isEmpty())
            throw invalid("表单默认值为空、重复或超过数量限制：" + f.name());
        // 未填写上游时仍验证对象范围和表单固定范围；动态联动在候选/提交时复核。
        SelectionFields.Presentation fixed =
                new Presentation(
                        p.appearance(), p.rootIds(), p.includeDescendants(), null, null, null);
        List<String> available =
                scope(selectedOptions(f, o, selected), fixed, Map.of()).stream()
                        .filter(v -> !v.disabled() && !v.unavailable())
                        .map(Option::value)
                        .toList();
        if (!available.containsAll(selected)) throw invalid("表单默认值不存在、已停用或超出可选范围：" + f.name());
    }

    /** 对象发布时的选择值迁移目标校验，没有应用：挑取值按对象设计器例外口径解析（见 inObjectDesign）。 */
    @Override
    public void validateTargets(
            FieldDefinition field, DataCenter.FieldOptions options, Collection<String> values) {
        var available =
                inObjectDesign(() -> selectedOptions(field, options, values)).stream()
                        .filter(o -> !o.disabled() && !o.unavailable())
                        .map(Option::value)
                        .collect(java.util.stream.Collectors.toSet());
        if (!available.containsAll(values)) throw invalid("迁移目标不存在、已停用或超出字段允许范围：" + field.name());
    }

    public static boolean enabled(Integer status) {
        return CommonStatusEnum.ENABLE.getStatus().equals(status);
    }
}

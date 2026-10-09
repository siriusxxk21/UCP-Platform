package com.lingan.ucp.nocode.application.service.resource;

import static com.lingan.ucp.nocode.api.NocodeErrorCodes.invalid;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lingan.ucp.framework.common.exception.ServiceException;
import com.lingan.ucp.nocode.api.*;
import com.lingan.ucp.nocode.application.dal.dataobject.NocodeApplicationDO;
import com.lingan.ucp.nocode.application.dal.mapper.ApplicationMapper;
import com.lingan.ucp.nocode.enums.*;

import jakarta.annotation.Resource;

import org.springframework.stereotype.Component;

import java.util.*;
import java.util.function.Function;

/**
 * 同一字段只能由一处写入：应用里的自动更新（事件赋值、维护汇总）与留存动作的目标字段， 不能同时在数据对象上配置数据联动或公式默认值。两套能力都保留，只是不能落在同一个字段上——
 * 否则保存时对象规则会按自己的结果覆盖自动更新写入的值，两边互相打架且没有任何报错。
 *
 * <p>双向校验：应用发布时检查本应用的写入目标；对象发布时检查所有已发布应用的写入目标。
 */
@Component
public class ApplicationRuleWriteExclusion {
    @Resource private ApplicationAutomationCatalog catalog;
    @Resource private ApplicationMapper applications;
    @Resource private ObjectMapper json;

    /** 应用资源对某个对象字段的写入；kind 为「自动更新」或「留存动作」。 */
    public record Writer(
            String applicationId,
            String kind,
            String resourceName,
            String objectId,
            String fieldId) {}

    /** 应用定义里会写入对象字段的资源：生效的自动更新与留存动作；已停用的自动更新不算。 */
    public List<Writer> writers(String applicationId, ApplicationCenter.Definition definition) {
        List<Writer> result = new ArrayList<>();
        for (ApplicationCenter.Resource resource : definition.resources()) {
            if (ApplicationResourceKindEnum.AUTOMATION.matches(resource.kind())) {
                ApplicationAutomations.Config config = catalog.config(resource);
                if (Boolean.FALSE.equals(config.enabled()) || config.assignments() == null)
                    continue;
                for (ApplicationAutomations.Assignment assignment : config.assignments())
                    result.add(
                            new Writer(
                                    applicationId,
                                    "自动更新",
                                    resource.name(),
                                    config.targetObjectId(),
                                    assignment.fieldId()));
            } else if (ApplicationResourceKindEnum.ACTION.matches(resource.kind())
                    && BusinessActionKindEnum.CAPTURE_VALUES.matches(
                            Objects.toString(resource.config().get("kind"), ""))) {
                ApplicationBusiness.Action action =
                        json.convertValue(resource.config(), ApplicationBusiness.Action.class);
                if (action.captures() == null) continue;
                for (String field : action.captures().keySet())
                    result.add(
                            new Writer(
                                    applicationId,
                                    "留存动作",
                                    resource.name(),
                                    action.objectId(),
                                    field));
            }
        }
        return result;
    }

    /**
     * 应用发布：本应用的自动更新、留存动作不能写入配了数据联动或公式默认值的字段。
     *
     * @param pinned 本应用固定的对象版本定义，键为对象 ID
     * @param published 对象当前发布版定义；未发布或不可用时返回 null 或抛业务异常
     */
    public void validatePublish(
            String applicationId,
            ApplicationCenter.Definition candidate,
            Map<String, DataCenter.Definition> pinned,
            Function<String, DataCenter.Definition> published) {
        for (Writer writer : writers(applicationId, candidate)) {
            // 固定版本决定本应用运行时的规则；当前发布版决定其它入口的规则。任一版本配了规则都算冲突。
            List<DataCenter.Definition> versions = new ArrayList<>();
            if (pinned.get(writer.objectId()) != null) versions.add(pinned.get(writer.objectId()));
            try {
                DataCenter.Definition current = published.apply(writer.objectId());
                if (current != null) versions.add(current);
            } catch (ServiceException unavailable) {
                // 目标对象不可用由自动更新自身的发布校验报错，这里不重复判断。
            }
            for (DataCenter.Definition definition : versions) {
                String rule = ruleLabel(definition, writer.fieldId());
                if (rule != null) throw invalid(publishMessage(writer, definition, rule));
            }
        }
    }

    /** 对象发布：配了数据联动或公式默认值的字段，不能同时是已发布应用里生效的自动更新或留存动作的目标字段；返回可读的冲突说明。 */
    public List<String> conflicts(DataCenter.Definition proposed) {
        List<Writer> writers = new ArrayList<>();
        for (ApplicationAutomations.Published published : catalog.published())
            writers.addAll(writers(published.applicationId(), published.definition()));
        return conflicts(proposed, writers, this::applicationName);
    }

    /** 纯判定入口：不读数据库，便于回归。 */
    public static List<String> conflicts(
            DataCenter.Definition proposed,
            List<Writer> writers,
            Function<String, String> applicationName) {
        List<String> result = new ArrayList<>();
        for (Writer writer : writers) {
            if (!proposed.objectId().equals(writer.objectId())) continue;
            String rule = ruleLabel(proposed, writer.fieldId());
            if (rule == null) continue;
            result.add(
                    "字段「"
                            + fieldName(proposed, writer.fieldId())
                            + "」配置了"
                            + rule
                            + "，但应用“"
                            + applicationName.apply(writer.applicationId())
                            + "”的"
                            + writer.kind()
                            + "“"
                            + writer.resourceName()
                            + "”也在写入该字段；同一字段只能由一处写入，请先去掉该字段的"
                            + rule
                            + "，或在应用中调整该"
                            + writer.kind()
                            + "并发布");
        }
        return result.stream().distinct().toList();
    }

    public static String publishMessage(
            Writer writer, DataCenter.Definition definition, String rule) {
        return writer.kind()
                + "“"
                + writer.resourceName()
                + "”要写入的字段「"
                + fieldName(definition, writer.fieldId())
                + "」在数据对象“"
                + definition.objectName()
                + "”上配置了"
                + rule
                + "；同一字段只能由一处写入，请改用其它目标字段，或先在数据对象上去掉该字段的"
                + rule;
    }

    /** 字段上会写值的对象规则名称；没有（或字段已停用）时返回 null。主表字段与内部明细字段都看。 */
    public static String ruleLabel(DataCenter.Definition definition, String fieldId) {
        DataCenter.FieldOptions options = options(definition, fieldId);
        if (options == null
                || options.rules() == null
                || MemberStateEnum.INACTIVE.matches(options.state())) return null;
        if (options.rules().linkage() != null) return "数据联动";
        if (options.rules().defaultFormula() != null) return "公式默认值";
        return null;
    }

    private static DataCenter.FieldOptions options(DataCenter.Definition definition, String id) {
        if (definition.fieldOptions() != null && definition.fieldOptions().containsKey(id))
            return definition.fieldOptions().get(id);
        if (definition.details() != null)
            for (DataCenter.Detail detail : definition.details())
                if (detail.fieldOptions() != null && detail.fieldOptions().containsKey(id))
                    return detail.fieldOptions().get(id);
        return null;
    }

    private static String fieldName(DataCenter.Definition definition, String id) {
        for (FieldDefinition field : definition.fields())
            if (id.equals(field.id())) return field.name();
        if (definition.details() != null)
            for (DataCenter.Detail detail : definition.details())
                for (FieldDefinition field : detail.fields())
                    if (id.equals(field.id())) return detail.name() + " · " + field.name();
        return id;
    }

    private String applicationName(String applicationId) {
        try {
            NocodeApplicationDO app = applications.selectById(Long.parseLong(applicationId));
            return app == null || app.getAppName() == null ? applicationId : app.getAppName();
        } catch (NumberFormatException unparsable) {
            return applicationId;
        }
    }
}

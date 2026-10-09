package com.richuang.os.nocode.api;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/** 应用专属业务配置。字典仅用于页面参数，不改变全局对象的字段定义。 */
public final class ApplicationBusiness {
    private ApplicationBusiness() {}

    public record Dictionary(String sourceType, List<DataCenter.Option> items) {}

    /** 业务编号写入已有唯一文本字段；原生 AUTO_NUMBER 继续由数据库身份列生成。 */
    public record NumberRule(
            String objectId, String fieldId, String prefix, String period, int width) {}

    public record Action(
            String objectId,
            String kind,
            Map<String, Object> values,
            String processDefinitionId,
            Map<String, String> variables,
            @JsonInclude(JsonInclude.Include.NON_EMPTY) Map<String, String> captures) {
        /** 兼容旧动作；留存映射的键是目标普通字段，值是同对象来源公式字段。 */
        public Action(
                String objectId,
                String kind,
                Map<String, Object> values,
                String processDefinitionId,
                Map<String, String> variables) {
            this(objectId, kind, values, processDefinitionId, variables, Map.of());
        }

        public Action {
            captures = captures == null ? Map.of() : Map.copyOf(captures);
        }
    }

    public record Execute(
            String applicationId,
            String objectId,
            String actionId,
            String recordId,
            String expectedRevision) {}
}

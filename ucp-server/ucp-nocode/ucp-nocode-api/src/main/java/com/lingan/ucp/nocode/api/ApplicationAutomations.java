package com.lingan.ucp.nocode.api;

import java.util.List;
import java.util.Set;

/** 跨对象自动更新的发布契约。来源和目标均固定在所属应用的对象版本中。 */
public final class ApplicationAutomations {
    private ApplicationAutomations() {}

    /** 按日期自动执行专用的「本条记录」：目标对象就是来源对象，目标记录就是来源记录本身，不经关系。 */
    public static final String SELF = "SELF";

    public record Binding(String relationId, String direction) {
        public boolean self() {
            return SELF.equals(direction);
        }
    }

    /** EXISTS 的 value/emptyValue 分别表示有有效来源和无有效来源时的实际写入值。 */
    public record Assignment(
            String fieldId, String kind, String sourceFieldId, Object value, Object emptyValue) {}

    /**
     * @param dateFieldId 按日期自动执行：来源上的日期 / 日期时间字段；其他执行方式为 null
     * @param offsetDays 按日期自动执行：执行日 = 日期 + offsetDays（负数 = 提前）；null 视为 0
     */
    public record Config(
            String objectId,
            String targetObjectId,
            Boolean enabled,
            String mode,
            Set<String> events,
            DataScope conditions,
            Binding binding,
            List<Assignment> assignments,
            String dateFieldId,
            Integer offsetDays) {
        /** 事件赋值与持续维护不带日期参数。 */
        public Config(
                String objectId,
                String targetObjectId,
                Boolean enabled,
                String mode,
                Set<String> events,
                DataScope conditions,
                Binding binding,
                List<Assignment> assignments) {
            this(
                    objectId,
                    targetObjectId,
                    enabled,
                    mode,
                    events,
                    conditions,
                    binding,
                    assignments,
                    null,
                    null);
        }
    }

    /** 仅供服务端枚举所有已发布应用；不向运行客户端暴露其他应用配置。 */
    public record Published(
            String applicationId, int version, ApplicationCenter.Definition definition) {}
}

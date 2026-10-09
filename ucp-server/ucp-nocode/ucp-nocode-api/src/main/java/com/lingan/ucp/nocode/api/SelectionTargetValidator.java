package com.lingan.ucp.nocode.api;

import java.util.Collection;
import java.util.List;

/** 发布和迁移复用运行层来源校验；元数据模块不直接依赖组织、字典或应用服务。 */
public interface SelectionTargetValidator {
    void validatePresentation(
            FieldDefinition field,
            DataCenter.FieldOptions options,
            SelectionFields.Presentation presentation);

    void validateDefinition(FieldDefinition field, DataCenter.FieldOptions options);

    void validateTargets(
            FieldDefinition field, DataCenter.FieldOptions options, Collection<String> values);

    /** 字段实际生效的候选定义：局部选项取 options，公共字典取字典项；不读取业务记录。挑取值的发布校验据此判断来源字段是否有选项。 */
    List<SelectionFields.Option> options(FieldDefinition field, DataCenter.FieldOptions options);

    /** 应用设计时的校验在给定对象版本集合内执行（挑取值的来源按这些版本解析）；缺省实现直接执行。 */
    default <T> T inDefinitions(
            java.util.Map<String, DataCenter.Definition> definitions,
            java.util.function.Supplier<T> action) {
        return action.get();
    }
}

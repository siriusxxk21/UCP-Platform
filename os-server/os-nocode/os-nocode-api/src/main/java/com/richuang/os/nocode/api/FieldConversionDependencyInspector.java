package com.richuang.os.nocode.api;

import java.util.List;
import java.util.Set;

/** 字段转换共用的依赖检查扩展点；实现读取实际配置，不以对象级引用代替字段影响。 */
public interface FieldConversionDependencyInspector {
    /** 转换提示来源独立于正式对象依赖登记，不改变既有依赖枚举。 */
    enum SourceKind {
        OBJECT,
        APPLICATION,
        DATASET
    }

    /** 当前对象使用拟发布定义，外部资源使用生效版本；结果包含可定位的解除入口。 */
    List<Impact> inspect(
            DataCenter.Definition previous, DataCenter.Definition proposed, Set<String> fieldIds);

    /** clearedFieldIds 仅含确有历史值将被清空的列，用于检查不会随直接清空执行的持久值维护。 */
    default List<Impact> inspect(
            DataCenter.Definition previous,
            DataCenter.Definition proposed,
            Set<String> fieldIds,
            Set<String> clearedFieldIds) {
        return inspect(previous, proposed, fieldIds);
    }

    record Impact(
            String fieldId,
            String sourceKind,
            String sourceId,
            String sourceName,
            String location,
            String message,
            String route,
            boolean blocking) {}
}

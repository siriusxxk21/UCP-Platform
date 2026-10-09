package com.richuang.os.nocode.metadata.service.object;

import com.richuang.os.nocode.api.DataCenter.*;
import com.richuang.os.nocode.api.ObjectOperationPreview.Impact;
import com.richuang.os.nocode.enums.*;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 统一生命周期诊断的位置和解除路径，不把数据中心变为应用配置或业务写入口。 */
final class ObjectOperationImpacts {
    private static final Pattern APPLICATION = Pattern.compile("application:([1-9][0-9]*):.*");

    private ObjectOperationImpacts() {}

    static String objectRoute(String id) {
        return "/nocode/object/editor?id=" + id;
    }

    static Impact own(
            ObjectOperationCheckEnum code,
            Definition definition,
            String fieldId,
            String location,
            String message,
            String resolution) {
        return new Impact(
                code.getCode(),
                true,
                "OBJECT",
                definition.objectId(),
                definition.objectName(),
                fieldId,
                location,
                message,
                resolution,
                objectRoute(definition.objectId()));
    }

    static Impact dependency(Dependency dependency, String fieldId, boolean blocking) {
        Matcher application = APPLICATION.matcher(dependency.sourceKey());
        boolean app = DependencyKindEnum.APP.matches(dependency.sourceKind());
        String id = app && application.matches() ? application.group(1) : dependency.sourceKey();
        String route = app && application.matches() ? "/nocode-app/workspace?id=" + id : null;
        String location =
                app
                        ? "应用中心 / 已引用对象 / " + dependency.sourceKey()
                        : dependency.sourceKind() + " / " + dependency.sourceKey();
        return new Impact(
                ObjectOperationCheckEnum.DEPENDENCY.getCode(),
                blocking,
                dependency.sourceKind(),
                id,
                dependency.sourceName(),
                fieldId,
                location,
                blocking ? "该资源仍引用此对象或字段" : "该应用仍引用此字段；发布时会检查是否需要暂停",
                app ? "兼容引用可继续运行；不兼容应用需确认暂停，适配后分别发布启用" : "前往该来源资源解除或调整此处引用，再重新检查",
                route);
    }
}

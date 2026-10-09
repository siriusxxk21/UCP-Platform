package com.richuang.os.nocode.report.service.authorization;

import com.richuang.os.nocode.api.ApplicationAuthorization.ObjectGrant;
import com.richuang.os.nocode.api.ReportDatasets;
import com.richuang.os.nocode.enums.ApplicationActionEnum;
import com.richuang.os.nocode.enums.ApplicationScopeEnum;
import com.richuang.os.nocode.report.service.dataset.ReportDatasetUsage;

import jakarta.annotation.Resource;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;

/** 报表来源权限暂时简化；保留旧策略和显式兼容开关，默认不依赖对象上限或成员数据策略。 */
@Component
public class ReportSourcePermissions {
    @Value("${nocode.report.source-permissions-enabled:false}")
    private boolean enabled;

    @Resource private ReportDatasetUsage usage;

    public boolean enabled() {
        return enabled;
    }

    /** 仅授予已校验来源的读取和导出；租户、发布版本、资源 ACL 及应用入口仍由原链路检查。 */
    public Map<String, List<ObjectGrant>> defaults(ReportDatasets.ResolvedSource source) {
        Map<String, Set<String>> fields = usage.fields(source);
        Map<String, List<ObjectGrant>> grants = new LinkedHashMap<>();
        for (ReportDatasets.ObjectReference ref : source.objects()) {
            grants.put(
                    ref.objectId(),
                    List.of(
                            new ObjectGrant(
                                    ref.objectId(),
                                    Set.of(
                                            ApplicationActionEnum.READ.getCode(),
                                            ApplicationActionEnum.EXPORT.getCode()),
                                    ApplicationScopeEnum.ALL.getCode(),
                                    fields.get(ref.objectId()),
                                    Set.of(),
                                    Set.of(),
                                    Set.of())));
        }
        return Map.copyOf(grants);
    }
}

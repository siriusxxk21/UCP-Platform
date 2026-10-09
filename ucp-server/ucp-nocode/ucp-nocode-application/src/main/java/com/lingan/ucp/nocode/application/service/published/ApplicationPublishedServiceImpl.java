package com.lingan.ucp.nocode.application.service.published;

import static com.lingan.ucp.nocode.api.NocodeErrorCodes.invalid;

import com.lingan.ucp.nocode.api.ApplicationCenter;
import com.lingan.ucp.nocode.api.work.PublishedResourceRef;
import com.lingan.ucp.nocode.application.service.application.ApplicationService;
import com.lingan.ucp.nocode.enums.ApplicationResourceKindEnum;

import jakarta.annotation.Resource;

import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.function.Supplier;

/** 精确解析已发布资源；不存在或校验失败时拒绝，绝不回退当前最新版。 */
@Service
public class ApplicationPublishedServiceImpl implements ApplicationPublishedService {
    @Resource private ApplicationService applications;
    @Resource private ApplicationVersionContext context;

    @Override
    public ApplicationCenter.Published getCurrent(String applicationId) {
        return applications.published(applicationId, null);
    }

    @Override
    public ApplicationCenter.Published getVersion(String applicationId, int version) {
        if (version < 1) throw invalid("请选择有效的应用发布版本");
        return applications.published(applicationId, version);
    }

    @Override
    public ApplicationCenter.Resource resolve(PublishedResourceRef reference) {
        if (reference == null
                || reference.applicationId() == null
                || reference.resourceId() == null
                || reference.resourceId().isBlank()
                || reference.resourceKind() == null
                || reference.applicationChecksum() == null) throw invalid("缺少固定资源引用");
        ApplicationResourceKindEnum.fromCode(reference.resourceKind());
        var release = getVersion(reference.applicationId(), reference.applicationVersion());
        if (!Objects.equals(reference.applicationChecksum(), release.checksum()))
            throw invalid("应用发布版本校验失败");
        return release.definition().resources().stream()
                .filter(
                        r ->
                                Objects.equals(r.id(), reference.resourceId())
                                        && Objects.equals(r.kind(), reference.resourceKind()))
                .findFirst()
                .orElseThrow(() -> invalid("固定版本中不存在所引用的业务资源"));
    }

    @Override
    public <T> T withVersion(PublishedResourceRef reference, Supplier<T> action) {
        if (action == null) throw invalid("缺少业务操作");
        resolve(reference);
        return context.execute(reference.applicationId(), reference.applicationVersion(), action);
    }
}

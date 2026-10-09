package com.richuang.os.nocode.application.service.published;

import com.richuang.os.nocode.api.ApplicationCenter;
import com.richuang.os.nocode.api.work.PublishedResourceRef;

import java.util.function.Supplier;

/** 已发布资源服务；调用方仍须校验配置／任务资格和当前业务权限。 */
public interface ApplicationPublishedService {
    /** 普通业务表单首次建草稿必须取当前发布，不接受客户端自行降级旧表单。 */
    ApplicationCenter.Published getCurrent(String applicationId);

    ApplicationCenter.Published getVersion(String applicationId, int version);

    ApplicationCenter.Resource resolve(PublishedResourceRef reference);

    /** 在同步调用中固定资源版本；不授予业务读写权，不自动开启独立事务。 */
    <T> T withVersion(PublishedResourceRef reference, Supplier<T> action);
}

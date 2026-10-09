package com.richuang.os.nocode.api;

/** 应用、页面、视图和流程模块调用的对象契约；不对外暴露为任意依赖登记 HTTP 接口。 */
public interface DataObjectApi {
    DataCenter.Definition getPublished(String objectId);

    /** 读取精确发布版本；不允许把草稿或停用对象用于应用运行。versionNo 为空时解析当前发布版本。 */
    PublishedObject getVersion(String objectId, Integer versionNo);

    record PublishedObject(
            String objectId, int versionNo, String checksum, DataCenter.Definition definition) {}

    void registerDependency(DataCenter.Dependency dependency, long actorId);

    void removeDependencies(String sourceKind, String sourceKey, long actorId);
}

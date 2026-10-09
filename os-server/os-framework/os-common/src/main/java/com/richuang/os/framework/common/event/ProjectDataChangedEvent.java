package com.richuang.os.framework.common.event;

/**
 * 跨模块项目数据变更事件。
 *
 * <p>发布方只声明受影响的项目，项目概览模块负责决定需要清理哪些内部缓存。</p>
 */
public record ProjectDataChangedEvent(Long projectId) {
}

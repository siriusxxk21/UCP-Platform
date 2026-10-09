package com.lingan.ucp.nocode.api;

import com.lingan.ucp.nocode.api.DataCenter.Check;
import com.lingan.ucp.nocode.api.DataCenter.Definition;

import java.util.List;

/**
 * 业务文件发布预检扩展点。
 *
 * <p>预检需要读取运行期的上传会话与目录绑定（运行时模块数据），而发布编排位于 schema 模块； 依赖方向不可反向，因此接口落在 api 契约层，由运行时模块提供实现，发布侧经
 * ObjectProvider 按需装配。未装配时发布预检跳过业务文件检查，不影响既有发布链路。
 */
public interface BusinessFilePublishInspection {

    /**
     * 输出业务文件相关发布检查项
     *
     * @param objectId 对象 ID
     * @param draft 本次待发布的完整定义
     * @param published 当前已发布定义，从未发布时为 null
     */
    List<Check> inspect(String objectId, Definition draft, Definition published);
}

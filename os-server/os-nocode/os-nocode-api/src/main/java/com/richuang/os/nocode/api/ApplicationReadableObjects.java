package com.richuang.os.nocode.api;

import java.util.List;

/**
 * 应用「因关联而可读取」的对象：应用没有引用它们，系统自动放行只读，用来完成引用选择、名称显示和计算。
 *
 * <p>它们不进「已引用对象」，不能为它们建列表、表单、相关列表或任务入口，也不能写；要那样用必须显式引用。
 */
public final class ApplicationReadableObjects {
    private ApplicationReadableObjects() {}

    /** objects 是工作台当前草稿里的引用（可能还没保存）；服务端按版本号与校验和核对。applicationId 用来判断数据管理员是否已对本应用关闭某个对象。 */
    public record Query(String applicationId, List<ApplicationCenter.ObjectReference> objects) {}

    /** kind 取 ImpliedViaEnum 的编码；name 是关系名，或配了规则 / 计算的那个字段的名称。 */
    public record Via(String fromObjectId, String fromObjectName, String kind, String name) {}

    /** object 是隐式对象的最新发布版；closed 为真表示数据管理员撤销了这个应用对它的授权。 */
    public record Item(DataObjectApi.PublishedObject object, List<Via> via, boolean closed) {}
}

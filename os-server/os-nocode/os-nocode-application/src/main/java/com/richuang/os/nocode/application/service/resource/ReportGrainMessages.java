package com.richuang.os.nocode.application.service.resource;

/**
 * 统计粒度相关的报错文案：保存、发布、预览与运行期遇到同一情形给同一句话。
 *
 * <p>「字段在明细里、当前粒度用不了」与「真的没授权」是两回事：前一类不出现「未授权」字样，免得搭建的人去改权限；后一类以 {@link #UNAUTHORIZED}
 * 原句开头，再说明是哪个对象的哪个字段、去哪里授权。
 */
public final class ReportGrainMessages {
    private ReportGrainMessages() {}

    public static final String UNAUTHORIZED = "统计涉及未授权字段或操作，不能通过分组、筛选或指标推算隐藏数据";
    public static final String DETAIL_DATE_FIELD = "按明细行统计时，日期范围字段仍须是主表的日期字段";
    public static final String DETAIL_DRILL_VIEW = "按明细行统计时暂不支持选择下钻明细视图，下钻会直接显示命中的明细行";
    public static final String ROOT_COUNT_NEEDS_DETAIL = "「主记录数」只用于按明细行统计；按主记录统计时请用「记录计数」";
    public static final String ROOT_COUNT_WITHOUT_FIELD = "「主记录数」无需指定字段";
    public static final String GRAIN_INVALID = "统计粒度无效";
    public static final String DETAIL_REQUIRED = "按明细行统计需要选择一个内部明细";
    public static final String DETAIL_MISSING = "统计所选的内部明细不存在";
    public static final String ROOT_WITH_DETAIL = "按主记录统计不能指定内部明细";
    public static final String VIEW_DRILL = "按明细行的统计暂不支持在数据视图里下钻";

    /** 按主记录统计时用到了某个明细里的字段，或以明细上的关系开头的路径。 */
    public static String rootGrain(String field, String detail) {
        return "「"
                + field
                + "」是明细「"
                + detail
                + "」里的字段，当前统计按主记录汇总，不能用它分组、筛选或计算。请把「统计粒度」改为「按明细行 · "
                + detail
                + "」";
    }

    /** 按明细行统计时用到了另一个明细里的字段或关系。 */
    public static String otherDetail(String field, String other, String detail) {
        return "「" + field + "」属于明细「" + other + "」，当前统计按明细「" + detail + "」的行汇总，一张统计只能用一个明细里的字段";
    }

    /** 成员看不到统计所按的明细；没有明细字段级授权，能看明细就能用它的全部字段。 */
    public static String detailRead(String detail) {
        return "没有明细「"
                + detail
                + "」的查看权限，不能按它的行做统计。请在应用的「成员与权限」里为该成员勾选可查看内部明细「"
                + detail
                + "」；数据对象还没有把这个明细授权给本应用时，先到数据中心该对象的「应用共享授权」里勾选";
    }

    /** 主表字段或关系目标对象的字段不在成员的可查看字段里。 */
    public static String fieldRead(String object, String field) {
        return UNAUTHORIZED
                + "：没有「"
                + object
                + "」的字段「"
                + field
                + "」的查看权限。请在应用的「成员与权限」里为该成员勾选这个可查看字段；数据对象还没有把它授权给本应用时，先到数据中心该对象的「应用共享授权」里勾选";
    }

    /** 一条主记录有几条明细，主表字段就被算几遍：求和、平均、非空计数都会失真。 */
    public static String mainAggregate(String field, String detail) {
        return "按明细行统计时，主表字段「"
                + field
                + "」会按明细行数重复计算，不能求和、求平均或做非空计数。请改用明细「"
                + detail
                + "」里的字段，或把「统计粒度」改回「按主记录」";
    }

    /** 明细粒度统计的下钻明细视图须是按同一明细逐行显示的数据视图：下钻出来一行一条明细，条数与金额才对得上格子。 */
    public static String detailDrillView(String detail) {
        return "按明细行统计时，下钻明细视图须是按明细「"
                + detail
                + "」逐行显示的数据视图（视图设置里「一行表示」选「一条内部明细」、明细来源选「"
                + detail
                + "」）";
    }

    public static String detailInactive(String detail) {
        return "统计所选的内部明细「" + detail + "」已停用";
    }

    /** 关系路径的第二段只能是目标对象主表上的关系。 */
    public static String targetDetailRelation(String relation, String object) {
        return "「" + relation + "」是「" + object + "」的明细里的关系，统计路径只能沿主表上的单值关系";
    }
}

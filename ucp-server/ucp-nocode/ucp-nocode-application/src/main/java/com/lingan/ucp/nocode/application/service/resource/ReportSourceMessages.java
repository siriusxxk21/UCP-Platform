package com.lingan.ucp.nocode.application.service.resource;

/**
 * 统计视图「多个数据来源」的报错文案（契约 P1-CONTRACT 第 6 章，逐字）：保存、发布、预览与运行期遇到同一情形给同一句话。
 *
 * <p>附加来源上复用的现有文案（含按明细行统计的 M 系列与现有统计文案）在原文前加 {@link #prefix} 指明来源；本类里已自带来源名的文案不再加前缀； 来源 1
 * 的报错保持原文不加前缀。以「统计涉及未授权字段」开头的原句加前缀后仍包含该子串。
 */
public final class ReportSourceMessages {
    private ReportSourceMessages() {}

    /** 来源 1 的编码：结果里 {@code SourceSummary.id} 固定用它；附加来源不能用它作编码。 */
    public static final String MAIN = "main";

    public static final String DISPLAY = "多个数据来源目前只用于透视表和汇总表";
    public static final String TOO_MANY = "数据来源最多 4 个";
    public static final String CODE = "数据来源编码无效或重复";
    public static final String NAME = "请填写数据来源名称（最多 30 字）";
    public static final String SINGLE_NAME = "只有一个数据来源时不用填写来源名称";
    public static final String FORMULA_SOURCE = "计算指标不属于某个来源，不用选择数据来源";
    public static final String METRICS = "多个来源的统计最多 10 个指标";
    public static final String RECORD_PAGE = "多个来源的统计暂不能放在记录详情页里";
    public static final String DRILL_METRIC = "多个来源的统计请点具体指标查看明细";
    public static final String DRILL_SOURCE = "下钻的数据来源不存在或与指标不一致";
    public static final String SINGLE_SOURCE = "统计只有一个数据来源，不用指定来源";
    public static final String LABELS = "维度显示名应与维度一一对应，每个最多 30 字";
    public static final String NATIVE_TYPE = "统计字段的物理类型无法识别";

    public static final String R1 = "一个是引用其它对象的字段，另一个不是";
    public static final String R3 = "日期按值对齐时两边都须是日期字段（不含时间）；含时间的请按日、按月或按年分组";
    public static final String R4 = "两者不是同一套选项";
    public static final String R5 = "小数、金额、百分比字段只能与同一个字段对齐";
    public static final String R6 = "字段类型不同";

    /** 附加来源上复用现有文案时的前缀。 */
    public static String prefix(String source, String message) {
        return "来源「" + source + "」：" + message;
    }

    /** L6：维度个数不等。 */
    public static String dimensions(String source) {
        return "来源「" + source + "」要为每个行维度、列维度各指定一个对应字段";
    }

    /** L7：分组方式不同；bucket 为来源 1 的分组（按值 / 按日 / 按月 / 按年）。 */
    public static String bucket(String source, String field, String dimension, String bucket) {
        return "来源「" + source + "」的「" + field + "」要与「" + dimension + "」用同一种分组方式（" + bucket + "）";
    }

    /** L8：不能对齐，reason 为 R1–R6 之一。 */
    public static String align(String source, String field, String dimension, String reason) {
        return "来源「" + source + "」的「" + field + "」不能与「" + dimension + "」对齐：" + reason;
    }

    /** L8·R2：两者引用的不是同一个对象。 */
    public static String r2(String first, String second) {
        return "两者引用的不是同一个对象（「" + first + "」与「" + second + "」）";
    }

    /** L9：指标的来源不存在。 */
    public static String metricSource(String metric) {
        return "指标「" + metric + "」的数据来源不存在";
    }

    /** L11：某来源没有基础指标。 */
    public static String noMetric(String source) {
        return "来源「" + source + "」还没有指标，请为它加一个指标或删除这个来源";
    }

    /** L13：开放了日期范围但附加来源没有日期字段。 */
    public static String dateRequired(String source) {
        return "统计开放了日期范围，请为来源「" + source + "」指定日期范围字段";
    }

    /** L13b：没开放日期范围却给附加来源填了。 */
    public static String dateUnexpected(String source) {
        return "统计没有开放日期范围，来源「" + source + "」不用指定日期范围字段";
    }

    /** L14：筛选键映射不到。 */
    public static String filterMissing(String field, String source) {
        return "用户可筛选字段「" + field + "」在来源「" + source + "」里没有对应字段，请在该来源的「筛选对应」里指定，或把它从可筛选字段里去掉";
    }

    /** L15：筛选对应的键不在 filterFieldIds。 */
    public static String filterUnknown(String source) {
        return "来源「" + source + "」的筛选对应用到了没有开放的筛选字段";
    }

    /** L16：筛选对应字段不相容，reason 同 L8 的 R1–R6。 */
    public static String filterIncompatible(
            String source, String field, String target, String reason) {
        return "来源「" + source + "」里与「" + field + "」对应的「" + target + "」不能用于同一个筛选：" + reason;
    }
}

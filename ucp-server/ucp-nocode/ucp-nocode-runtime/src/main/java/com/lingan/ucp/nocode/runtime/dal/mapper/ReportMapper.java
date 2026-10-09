package com.lingan.ucp.nocode.runtime.dal.mapper;

import com.lingan.ucp.nocode.runtime.dal.query.*;

import org.apache.ibatis.annotations.Mapper;

import java.util.List;
import java.util.Map;

/** 聚合、下钻共用同一条件编译器，所有业务值通过 MyBatis 绑定。 */
@Mapper
public interface ReportMapper {
    /** 同一语句快照返回分组、总组数和总体指标；组数限制不裁剪总体统计。 */
    /** 实际授权值去重、搜索、分页及计数在同一语句快照完成。 */
    String options(ReportOptionsStatement statement);

    String result(ReportStatement statement);

    /** 同一语句快照给出只读投影和总条数。 */
    String datasetDetails(ReportDetailStatement statement);

    /** 将维度和可选指标条件应用到同一授权范围，返回稳定排序的下钻记录。 */
    List<String> rows(ReportStatement statement);

    /** 与 rows 使用相同的下钻条件；三类统计查询均保留 20 秒超时。 */
    long count(ReportStatement statement);

    /**
     * 透视表：同一语句快照返回已排序、已截断的叶子行/列组表头，以及叶子、各层小计和合计格。 小计与合计由 GROUPING SETS
     * 按原始记录重新聚合，与叶子同一口径（去重计数、平均、极值、计算指标都在各层重算）。
     */
    String pivot(ReportStatement statement);

    /** 与 rows/count 使用同一段取数与下钻条件，返回全部命中记录的主键；供数据视图的统计下钻取交集。 */
    List<String> keys(ReportStatement statement);

    /** 明细粒度：与 keys 同一段取数与下钻条件，返回命中的明细行主键。 */
    List<String> detailKeys(ReportStatement statement);

    /**
     * 明细粒度的下钻行：与 count 同一段取数与下钻条件，按（主表主键, 明细行主键）排序分页。每个元素是两段文本组成的 JSON 数组：
     * 所属主记录的投影、这条明细行的投影（两段都与记录查询的投影同形）。参数由 {@link ReportStatement#withDetailRows} 给出。
     */
    List<String> detailRows(Map<String, Object> parameters);
}

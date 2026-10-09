package com.richuang.os.nocode.runtime.dal.mapper;

import com.richuang.os.nocode.runtime.dal.query.BizFileStatement;

import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/** 业务文件浏览语句：记录授权与字段安全集已由服务编译进条件，这里只渲染参数化 SQL。 */
@Mapper
public interface BizFileBrowseMapper {
    /** 对象内已建立记录目录的规则版本与可见文件计数；版本根节点同时作为各层策略解析来源。 */
    List<String> ruleVersions(BizFileStatement statement);

    /** 指定层级的分组目录节点。 */
    List<String> groupDirectories(BizFileStatement statement);

    long countGroupDirectories(BizFileStatement statement);

    /** 指定版本与分组位置的记录目录节点，按记录投影标签来源值。 */
    List<String> recordDirectories(BizFileStatement statement);

    long countRecordDirectories(BizFileStatement statement);

    /** 记录目录内的附件字段目录与明细区目录。 */
    List<String> recordChildren(BizFileStatement statement);

    /** 明细区内的明细行目录。 */
    List<String> regionChildren(BizFileStatement statement);

    long countRegionChildren(BizFileStatement statement);

    /** 明细行内的附件字段目录。 */
    List<String> rowChildren(BizFileStatement statement);

    /** 入口可见的业务文件分页列表；搜索按文件名匹配。 */
    List<String> files(BizFileStatement statement);

    long countFiles(BizFileStatement statement);

    /** 对象级可见文件统计，供业务空间列表与数量核对。 */
    String stats(BizFileStatement statement);

    /** 已有业务文件数据的对象标识；维护上下文据此枚举业务空间，不读取对象元数据设计态。 */
    List<String> boundObjects();
}

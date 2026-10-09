package com.richuang.os.nocode.runtime.dal.query;

import java.util.List;

/**
 * 业务文件浏览语句参数
 *
 * <p>由服务按固定对象版本、权限安全集与导航位置编译；标识符只来自对象契约与数据库目录， 业务值全部通过 MyBatis 绑定。分组键为名称来源字段不可读时只接受摘要令牌，
 * 不返回也不匹配绑定表保存的目录名称。
 */
public record BizFileStatement(
        String objectId,
        RecordStatement base,
        Integer ruleVersion,
        List<GroupFilter> groups,
        int directoryLevel,
        boolean directoryHashed,
        String recordId,
        String detailId,
        String rowId,
        String fieldId,
        Long entryId,
        String nameLike,
        List<Projected> labelFields,
        String visibleFields,
        String visibleDetails,
        int limit,
        int offset,
        List<Long> entryIds,
        List<SearchTitle> searchTitles) {

    public BizFileStatement {
        groups = groups == null ? List.of() : List.copyOf(groups);
        labelFields = labelFields == null ? List.of() : List.copyOf(labelFields);
        entryIds = entryIds == null ? List.of() : List.copyOf(entryIds);
        searchTitles = searchTitles == null ? List.of() : List.copyOf(searchTitles);
    }

    /** 分组筛选：level 为 1 或 2 的层级；hashed 表示该层名称来源字段不可读，键为服务端摘要令牌。 */
    public record GroupFilter(int level, String key, boolean hashed) {}

    /** 需随行投影的字段（标签来源）；列名来自对象契约映射。 */
    public record Projected(String fieldId, String column) {}

    /** 名称查询只使用当前授权且可直接检索的列，按绑定规则版本限定。 */
    public record SearchTitle(int ruleVersion, List<Projected> fields, List<TitlePart> parts) {}

    /** 模板片段：字段列或常量，常量始终参数绑定。 */
    public record TitlePart(String column, String literal) {}

    public BizFileStatement withSearchTitles(List<SearchTitle> titles) {
        return new BizFileStatement(
                objectId,
                base,
                ruleVersion,
                groups,
                directoryLevel,
                directoryHashed,
                recordId,
                detailId,
                rowId,
                fieldId,
                entryId,
                nameLike,
                labelFields,
                visibleFields,
                visibleDetails,
                limit,
                offset,
                entryIds,
                titles);
    }

    public BizFileStatement page(int size, int start) {
        return new BizFileStatement(
                objectId,
                base,
                ruleVersion,
                groups,
                directoryLevel,
                directoryHashed,
                recordId,
                detailId,
                rowId,
                fieldId,
                entryId,
                nameLike,
                labelFields,
                visibleFields,
                visibleDetails,
                size,
                start,
                entryIds,
                searchTitles);
    }

    /** 按受管节点身份收窄语句：内容读取用它定位单个绑定行；浏览语句不调用 */
    public BizFileStatement boundTo(Long entryId) {
        return new BizFileStatement(
                objectId,
                base,
                ruleVersion,
                groups,
                directoryLevel,
                directoryHashed,
                recordId,
                detailId,
                rowId,
                fieldId,
                entryId,
                nameLike,
                labelFields,
                visibleFields,
                visibleDetails,
                limit,
                offset,
                entryIds,
                searchTitles);
    }

    /** 按节点集合收窄语句：收藏/最近访问列表用它按标记顺序重取当前可见文件；浏览语句不调用 */
    public BizFileStatement withEntryIds(List<Long> entryIds) {
        return new BizFileStatement(
                objectId,
                base,
                ruleVersion,
                groups,
                directoryLevel,
                directoryHashed,
                recordId,
                detailId,
                rowId,
                fieldId,
                entryId,
                nameLike,
                labelFields,
                visibleFields,
                visibleDetails,
                limit,
                offset,
                entryIds,
                searchTitles);
    }
}

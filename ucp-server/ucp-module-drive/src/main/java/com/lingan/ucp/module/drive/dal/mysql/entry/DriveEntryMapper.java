package com.lingan.ucp.module.drive.dal.mysql.entry;

import cn.hutool.core.collection.CollUtil;

import com.lingan.ucp.framework.mybatis.core.mapper.BaseMapperX;
import com.lingan.ucp.framework.mybatis.core.query.LambdaQueryWrapperX;
import com.lingan.ucp.module.drive.dal.dataobject.entry.DriveEntryChainRow;
import com.lingan.ucp.module.drive.dal.dataobject.entry.DriveEntryDO;
import com.lingan.ucp.module.drive.dal.dataobject.entry.DriveEntryOriginRow;
import com.lingan.ucp.module.drive.enums.entry.DriveTrashStateEnum;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/**
 * 网盘节点 Mapper
 *
 * <p>目录结构查询依赖 PostgreSQL 递归 CTE，因此结构相关方法统一放在 XML 中维护。
 *
 * @author os
 */
@Mapper
public interface DriveEntryMapper extends BaseMapperX<DriveEntryDO> {

    /**
     * 尝试建立一个受管目录，成功返回 1 并回填 entry.id，同名冲突返回 0。
     *
     * <p>使用 PostgreSQL ON CONFLICT 避免唯一索引冲突将外层业务事务标记为失败。
     */
    int insertManagedFolderIfAbsent(@Param("entry") DriveEntryDO entry, @Param("actor") Long actor);

    /** 查询目录下的正常节点，目录在前、按名称排序 */
    List<DriveEntryDO> selectListByParent(
            @Param("spaceId") Long spaceId, @Param("parentId") Long parentId);

    /**
     * 查询回收站节点：只返回删除入口节点，子节点随入口节点一起恢复
     *
     * @param trashedBy 非空时只返回该用户删除的节点，空间管理者传 null 查看全部
     */
    List<DriveEntryDO> selectTrashList(
            @Param("spaceId") Long spaceId,
            @Param("name") String name,
            @Param("trashedBy") String trashedBy);

    /**
     * 查询节点到空间根目录的完整路径，自节点向上逐级返回
     *
     * <p>授权判定与面包屑都需要完整路径；继承边界的截断在各调用方按节点自身声明处理。
     */
    List<DriveEntryChainRow> selectPathChain(
            @Param("spaceId") Long spaceId, @Param("entryId") Long entryId);

    /** 查询节点及其全部子孙节点，不区分回收站状态 */
    List<DriveEntryDO> selectSubtree(@Param("spaceId") Long spaceId, @Param("rootId") Long rootId);

    /** 判断 ancestorId 是否为 entryId 自身或其祖先，用于防止目录移动到自己的子目录 */
    Boolean selectIsInSubtree(@Param("entryId") Long entryId, @Param("ancestorId") Long ancestorId);

    /** 按名称模糊搜索空间内节点 */
    List<DriveEntryDO> selectListByNameLike(
            @Param("spaceId") Long spaceId,
            @Param("name") String name,
            @Param("limit") Integer limit);

    /** 批量更新回收站状态，恢复时传入空的删除信息 */
    int updateTrashState(
            @Param("ids") Collection<Long> ids,
            @Param("trashState") String trashState,
            @Param("trashedAt") LocalDateTime trashedAt,
            @Param("trashedBy") String trashedBy);

    /** 记录删除入口节点的原位置，用于恢复时回到原目录 */
    int updateOriginParentId(@Param("id") Long id, @Param("originParentId") Long originParentId);

    default DriveEntryDO selectBySpaceAndParentAndName(Long spaceId, Long parentId, String name) {
        return selectOne(
                new LambdaQueryWrapperX<DriveEntryDO>()
                        .eq(DriveEntryDO::getSpaceId, spaceId)
                        .eq(DriveEntryDO::getParentId, parentId)
                        .eq(DriveEntryDO::getName, name)
                        .eq(DriveEntryDO::getTrashState, DriveTrashStateEnum.NORMAL.getCode()));
    }

    default Long selectCountByParent(Long spaceId, Long parentId) {
        return selectCount(
                new LambdaQueryWrapperX<DriveEntryDO>()
                        .eq(DriveEntryDO::getSpaceId, spaceId)
                        .eq(DriveEntryDO::getParentId, parentId));
    }

    default Long selectCountBySpace(Long spaceId) {
        return selectCount(
                new LambdaQueryWrapperX<DriveEntryDO>().eq(DriveEntryDO::getSpaceId, spaceId));
    }

    default List<DriveEntryDO> selectListByIds(Collection<Long> ids) {
        if (CollUtil.isEmpty(ids)) {
            return List.of();
        }
        return selectList(new LambdaQueryWrapperX<DriveEntryDO>().in(DriveEntryDO::getId, ids));
    }

    /** 查询引用指定文件内容的受管文件节点（正常状态），用于绑定幂等与清理前引用判断 */
    default List<DriveEntryDO> selectManagedListByFileId(Long fileId) {
        return selectList(
                new LambdaQueryWrapperX<DriveEntryDO>()
                        .eq(DriveEntryDO::getFileId, fileId)
                        .eq(DriveEntryDO::getManagedBiz, true)
                        .eq(DriveEntryDO::getTrashState, DriveTrashStateEnum.NORMAL.getCode()));
    }

    /** 业务空间文件夹区的目录列表：只返回普通节点 */
    List<DriveEntryDO> selectOrdinaryListByParent(
            @Param("spaceId") Long spaceId, @Param("parentId") Long parentId);

    /** 子树内按名称搜索（不含根自身，只含正常节点） */
    List<DriveEntryDO> selectSubtreeByNameLike(
            @Param("spaceId") Long spaceId,
            @Param("rootId") Long rootId,
            @Param("name") String name,
            @Param("limit") Integer limit);

    /** 子树内某人删除、且来源键相符的回收站入口节点（父节点未被删除的那些） */
    List<DriveEntryDO> selectTrashedInSubtree(
            @Param("spaceId") Long spaceId,
            @Param("rootId") Long rootId,
            @Param("trashedBy") String trashedBy,
            @Param("originKey") String originKey);

    /** 给节点打来源标记；已有标记的不动（ON CONFLICT (entry_id) DO NOTHING） */
    int insertOrigins(
            @Param("ids") Collection<Long> ids,
            @Param("originKey") String originKey,
            @Param("actor") Long actor);

    /** 一批节点的来源键；没有标记的节点不在结果里 */
    List<DriveEntryOriginRow> selectOrigins(@Param("ids") Collection<Long> ids);

    /** rootId 自身及其下所有未进回收站的节点里，来源键不是 originKey（含没有标记）的个数 */
    long countForeignInSubtree(
            @Param("spaceId") Long spaceId,
            @Param("rootId") Long rootId,
            @Param("originKey") String originKey);

    /** 建一个普通目录，同名冲突返回 0（ON CONFLICT DO NOTHING，不污染外层事务） */
    default int insertFolderIfAbsent(DriveEntryDO entry, Long actor) {
        return insertManagedFolderIfAbsent(entry, actor);
    }

    /** 业务空间内按名称前缀搜索受管节点 */
    default List<DriveEntryDO> selectManagedListByNameLike(
            Long spaceId, String nameLike, int limit) {
        return selectList(
                new LambdaQueryWrapperX<DriveEntryDO>()
                        .eq(DriveEntryDO::getSpaceId, spaceId)
                        .eq(DriveEntryDO::getManagedBiz, true)
                        .like(DriveEntryDO::getName, nameLike)
                        .last("LIMIT " + limit));
    }
}

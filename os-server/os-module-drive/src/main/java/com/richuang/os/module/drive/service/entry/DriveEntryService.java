package com.richuang.os.module.drive.service.entry;

import com.richuang.os.module.drive.controller.admin.entry.vo.DriveEntryBreadcrumbRespVO;
import com.richuang.os.module.drive.controller.admin.entry.vo.DriveEntryCopyReqVO;
import com.richuang.os.module.drive.controller.admin.entry.vo.DriveEntryFolderCreateReqVO;
import com.richuang.os.module.drive.controller.admin.entry.vo.DriveEntryInheritReqVO;
import com.richuang.os.module.drive.controller.admin.entry.vo.DriveEntryListReqVO;
import com.richuang.os.module.drive.controller.admin.entry.vo.DriveEntryMoveReqVO;
import com.richuang.os.module.drive.controller.admin.entry.vo.DriveEntryRenameReqVO;
import com.richuang.os.module.drive.controller.admin.entry.vo.DriveEntryRespVO;
import com.richuang.os.module.drive.controller.admin.entry.vo.DriveEntrySearchReqVO;
import com.richuang.os.module.drive.controller.admin.entry.vo.DriveTrashListReqVO;
import com.richuang.os.module.drive.dal.dataobject.entry.DriveEntryDO;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.io.InputStream;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 网盘节点 Service 接口
 *
 * <p>所有方法首参都以节点所在空间与节点编号定位内容，权限判定统一走 {@link
 * com.richuang.os.module.drive.service.permission.DrivePermissionService}。
 *
 * @author os
 */
public interface DriveEntryService {

    /**
     * 节点内容读取信息
     *
     * @param entry 文件节点
     * @param length 内容长度，存储侧无法确认时为 null
     */
    @Getter
    @AllArgsConstructor
    class DriveEntryContent {

        private final DriveEntryDO entry;
        private final Long length;
    }

    /** 查询目录下的节点列表 */
    List<DriveEntryDO> getEntryList(DriveEntryListReqVO reqVO, Long userId);

    /** 按名称搜索空间内节点，只返回调用方有权查看的结果 */
    List<DriveEntryDO> searchEntryList(DriveEntrySearchReqVO reqVO, Long userId);

    /** 获得节点，不存在或无权查看时抛出业务异常 */
    DriveEntryDO getEntry(Long id, Long userId);

    /** 获得节点详情，含当前用户在该节点上的角色与收藏状态 */
    DriveEntryRespVO getEntryDetail(Long id, Long userId);

    /** 获得当前用户对一组节点的收藏状态，供列表批量补齐 */
    Map<Long, Boolean> getFavoriteMap(Long userId, Collection<Long> entryIds);

    /** 获得节点路径，自空间根到当前节点 */
    List<DriveEntryBreadcrumbRespVO> getBreadcrumbList(Long entryId, Long userId);

    /**
     * 新建目录
     *
     * @return 节点编号
     */
    Long createFolder(DriveEntryFolderCreateReqVO reqVO, Long userId);

    /**
     * 上传文件，内容按流写入存储，节点名与物理路径分离
     *
     * @return 新建的文件节点
     */
    DriveEntryDO uploadFile(
            Long spaceId,
            Long parentId,
            String fileName,
            String contentType,
            long size,
            InputStream content,
            Long userId);

    /** 重命名节点 */
    void renameEntry(DriveEntryRenameReqVO reqVO, Long userId);

    /** 同空间内移动节点，目标不能是自身或自身的子目录 */
    void moveEntry(DriveEntryMoveReqVO reqVO, Long userId);

    /**
     * 设置节点是否继承上级授权，false 时该节点及其子树只按本级及以下授权判定
     *
     * <p>该设置改变授权边界，要求调用方对被设置节点具备可管理权限。
     */
    void updateInheritParent(DriveEntryInheritReqVO reqVO, Long userId);

    /**
     * 复制节点，可跨空间；目录按其子树深拷贝
     *
     * @return 目标节点编号
     */
    Long copyEntry(DriveEntryCopyReqVO reqVO, Long userId);

    /** 批量移入回收站，目录按子树整体处理 */
    void trashEntryList(Collection<Long> ids, Long userId);

    /**
     * 从回收站恢复节点及其子树，原位置不可用时回到空间根目录
     *
     * @return 恢复后的节点
     */
    DriveEntryDO restoreEntry(Long id, Long userId);

    /** 批量彻底删除：清理内容存储、授权、分享与标记，仅允许对回收站内容执行 */
    void purgeEntryList(Collection<Long> ids, Long userId);

    /** 查询回收站节点列表 */
    List<DriveEntryDO> getTrashList(DriveTrashListReqVO reqVO, Long userId);

    /** 获得文件节点的内容读取信息，含 Range 响应所需长度 */
    DriveEntryContent getContentInfo(Long id, Long userId);

    /** 按偏移量打开文件内容流，调用方负责关闭 */
    InputStream getContentStream(Long id, Long userId, long offset);

    /** 获得节点 Map，供收藏与最近使用列表补齐节点信息 */
    Map<Long, DriveEntryDO> getEntryMap(Collection<Long> ids);
}

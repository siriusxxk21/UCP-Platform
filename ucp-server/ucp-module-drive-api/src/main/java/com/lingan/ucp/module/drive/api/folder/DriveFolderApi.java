package com.lingan.ucp.module.drive.api.folder;

import com.lingan.ucp.module.drive.api.folder.dto.DriveFolderContent;
import com.lingan.ucp.module.drive.api.folder.dto.DriveFolderInfo;
import com.lingan.ucp.module.drive.api.folder.dto.DriveFolderNode;
import com.lingan.ucp.module.drive.api.folder.dto.DriveFolderScope;

import java.io.InputStream;
import java.util.Collection;
import java.util.List;

/**
 * 记录文件夹领域接口
 *
 * <p>供无代码运行时把「网盘里的一个真实文件夹」当作某条业务记录的文件夹来浏览与读写：调用方先按自己的业务规则（能看 / 能改这条记录）
 * 完成鉴权，再带着「限定子树」调用本接口；实现在限定子树内转调现有的网盘节点服务，上传、改名、移动、复制、删除的规则与网盘自己的入口完全相同。
 *
 * <p>限定子树的操作里，parentId / targetParentId 为 0 表示 scope 的根；根自身的编号不出现在任何返回值里，也不能被改名、移动、删除、复制。
 *
 * <p>来源规则：凭 scope 的可编辑角色（而不是本人的网盘角色）操作时，只能改名、移动、删除、恢复来源键与 scope.originKey 相同的节点；
 * 新建、上传、复制得到的节点打上 scope.originKey。本人在网盘里对节点本来就可编辑时不受此限。
 */
public interface DriveFolderApi {

    // —— 查询：不鉴权，只给节点状态，不给内容 ——

    /** 节点状态；不存在返回 null */
    DriveFolderInfo describe(Long entryId);

    /** 不带限定子树的有效角色编码；无权限返回 null */
    String roleOf(Long spaceId, Long entryId, Long userId);

    /** 「空间名 / 目录 / 目录」展示路径；节点不存在返回 null */
    String displayPath(Long entryId);

    // —— 系统建目录：不鉴权，调用方必须已完成业务鉴权 ——

    /** 在普通、未进回收站的目录下建一个普通目录并打上来源标记；同名时加「 (2)」起的序号。返回新目录编号 */
    Long ensureChild(Long parentEntryId, String name, Long operatorId, String originKey);

    // —— 限定子树的操作：parentId / targetParentId 为 0 表示 scope 的根 ——

    List<DriveFolderNode> list(DriveFolderScope scope, Long userId, Long parentId);

    DriveFolderNode get(DriveFolderScope scope, Long userId, Long id);

    /** 根以下的名称路径；id 是根的直接子节点时返回空列表 */
    List<String> path(DriveFolderScope scope, Long userId, Long id);

    Long createFolder(DriveFolderScope scope, Long userId, Long parentId, String name);

    DriveFolderNode upload(
            DriveFolderScope scope,
            Long userId,
            Long parentId,
            String fileName,
            String contentType,
            long size,
            InputStream content);

    void rename(DriveFolderScope scope, Long userId, Long id, String name);

    void move(DriveFolderScope scope, Long userId, Long id, Long targetParentId);

    Long copy(DriveFolderScope scope, Long userId, Long id, Long targetParentId);

    void trash(DriveFolderScope scope, Long userId, Collection<Long> ids);

    List<DriveFolderNode> search(DriveFolderScope scope, Long userId, String name, Integer limit);

    DriveFolderContent contentInfo(DriveFolderScope scope, Long userId, Long id);

    /** 按偏移量打开文件内容流，调用方负责关闭 */
    InputStream openContent(DriveFolderScope scope, Long userId, Long id, long offset);

    /** 最近删除：只列本人删的、且来源键与 scope.originKey 相符的回收站入口节点 */
    List<DriveFolderNode> trashList(DriveFolderScope scope, Long userId);

    DriveFolderNode restore(DriveFolderScope scope, Long userId, Long id);
}

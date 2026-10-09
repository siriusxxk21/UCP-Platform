package com.richuang.os.module.drive.api.bizfile;

import com.richuang.os.module.drive.api.bizfile.dto.DriveBizEntryDTO;
import com.richuang.os.module.drive.api.bizfile.dto.DriveBizFileContent;
import com.richuang.os.module.drive.api.bizfile.dto.DriveBizSpaceDTO;
import com.richuang.os.module.drive.api.bizfile.dto.DriveBizTemporaryContent;

import java.io.InputStream;
import java.util.List;

/**
 * 业务文件融合领域接口
 *
 * <p>供无代码运行时在业务保存事务内调用：建立受管目录、绑定/解绑附件、调整目录、 上传受保护内容，以及为业务文件浏览提供读取原语。全部方法按网盘节点编号寻址，
 * 不接收前端提交的目录路径；对象/记录/字段身份到节点的映射由无代码侧的绑定表维护。
 *
 * <p>事务语义：调用方持有业务事务，本接口的实现与其合并提交，任何校验失败抛出异常使整次业务保存回滚。 本接口不做普通网盘空间 ACL 校验——业务授权链由无代码侧在其保存与读取入口完成。
 */
public interface DriveBizFileApi {

    /** 按名称取得或建立业务空间（type=BIZ）；业务空间不归属个人或部门，配额独立。 */
    Long ensureBusinessSpace(String name, Long operatorId);

    /**
     * 按稳定编号解析业务空间；历史规则未保存编号时按名称取回或建立。
     *
     * <p>该方法用于新写入，已停用空间一律拒绝；既有内容读取不调用此方法。
     */
    Long ensureBusinessSpace(Long spaceId, String legacyName, Long operatorId);

    /** 列出可供对象设计器按稳定编号选择的业务空间。 */
    List<DriveBizSpaceDTO> listBusinessSpaces();

    /**
     * 上传受保护文件内容（业务附件专用入口）
     *
     * <p>写入网盘选定存储并登记受保护标记；与上传会话的关联由无代码侧维护。 不要求上传者具备普通网盘空间编辑权限。
     *
     * @param fileName 原始文件名
     * @param contentType 内容类型
     * @param size 内容大小（字节）
     * @param content 内容流，方法内消费完毕
     * @return 文件内容编号（infra_file.id）
     */
    Long uploadProtectedContent(
            String fileName, String contentType, long size, InputStream content);

    /**
     * 幂等建立业务目录并返回末层目录节点编号
     *
     * <p>自业务空间根逐层建立受管目录；已存在的同名受管目录直接复用（并发建立由唯一索引兜底重查）。 业务空间内不允许普通目录，因此不存在与普通目录同名合并的问题。
     *
     * @param spaceId 业务空间编号
     * @param path 从首层固定目录到末层（通常为附件字段目录）的展示名层级，不允许为空
     * @param operatorId 操作者（登记审计）
     * @return 末层目录节点编号（drive_entry.id）
     */
    Long ensureDirectory(Long spaceId, List<String> path, Long operatorId);

    /**
     * 在已确定的受管父目录下为一个新稳定身份建立独立目录。
     *
     * <p>展示名冲突时自动加序号，不复用其他业务身份的同名节点。
     */
    Long createManagedDirectory(
            Long spaceId, Long parentEntryId, String name, Long operatorId);

    /**
     * 绑定业务附件：在目标受管目录下建立受管文件节点
     *
     * <p>fileId 必须已按受保护方式写入文件底座；展示名、大小、类型由文件底座记录决定， 不由调用方另行提交，避免与实际内容不一致。同一目录下相同 fileId 幂等返回既有节点。
     * 同目录同名自动加序号，不覆盖既有文件。
     *
     * <p>返回节点信息（编号、展示名、大小、类型）供无代码侧登记浏览索引； 受管节点展示名在绑定后不可变，调用方可安全冗余保存。
     *
     * @param spaceId 业务空间编号
     * @param parentEntryId 目标受管目录节点编号
     * @param fileId 文件内容编号（infra_file.id）
     * @param sourceEntry 来源入口标识（应用/维护/任务），仅作来源信息
     * @param operatorId 操作者
     * @return 受管文件节点信息（drive_entry 投影）
     */
    DriveBizEntryDTO bindFile(
            Long spaceId, Long parentEntryId, Long fileId, String sourceEntry, Long operatorId);

    /**
     * 解除业务附件绑定：删除受管文件节点
     *
     * <p>仅删除逻辑节点并回收容量，不物理删除文件内容；历史引用与保留策略由无代码侧判断后再清理内容。
     *
     * @param entryId 受管文件节点编号
     * @param operatorId 操作者
     */
    void unbindFile(Long entryId, Long operatorId);

    /**
     * 删除受管目录
     *
     * <p>仅允许删除整棵子树内不含文件节点的受管目录（明细行删除、记录删除后清理空目录层级）； 子树内仍有文件时拒绝，由调用方先解绑文件。
     *
     * @param entryId 受管目录节点编号
     * @param operatorId 操作者
     */
    void removeDirectory(Long entryId, Long operatorId);

    /**
     * 调整业务记录目录：移动到新层级并更新显示名
     *
     * <p>业务值（编号、标题、年份、项目等）变更后由无代码侧调用；移动只改变逻辑节点， 子树与文件 ID 保持稳定。新层级中同名目录冲突时自动加序号，不覆盖已有目录。
     *
     * @param entryId 受管记录目录节点编号
     * @param newPath 从空间根到该记录目录的新展示名层级（含末层目录名）
     * @param operatorId 操作者
     */
    void moveDirectory(Long entryId, List<String> newPath, Long operatorId);

    /**
     * 查询文件内容是否仍被受管文件节点引用
     *
     * <p>供物理清理前判断；无代码侧还需结合其保留引用表共同判断。
     *
     * @param fileId 文件内容编号
     * @return 存在有效受管节点引用返回 true
     */
    boolean hasManagedEntry(Long fileId);

    /**
     * 读取受管文件节点的内容信息（业务附件内容读取原语）
     *
     * <p>节点必须是业务空间内的受管文件；不做普通网盘 ACL 校验，业务授权链由无代码侧在调用前完成。 供内容响应确定名称、类型与 Range 所需长度。
     *
     * @param entryId 受管文件节点编号
     * @return 内容读取信息
     */
    DriveBizFileContent contentInfo(Long entryId);

    /**
     * 按偏移量打开受管文件节点的内容流，调用方负责关闭
     *
     * <p>供 Range 断点读取；节点身份在每次调用时重新确认，不是受管业务文件时抛出异常使该次读取失败。 内容底座记录缺失时抛出异常，不返回空流。
     *
     * @param entryId 受管文件节点编号
     * @param offset 起始偏移量，0 表示从头读取
     * @return 内容流
     */
    InputStream openContent(Long entryId, long offset);

    /**
     * 读取临时上传内容的信息
     *
     * <p>面向尚未绑定业务节点的受保护内容：仅确认内容存在且带受保护标记，会话归属、有效期与 可用状态由无代码侧判断。不要求普通网盘空间权限，也不建立目录节点。
     *
     * @param fileId 文件内容编号（infra_file.id）
     * @return 临时内容读取信息
     */
    DriveBizTemporaryContent temporaryContentInfo(Long fileId);

    /**
     * 按偏移量打开临时上传内容流，调用方负责关闭
     *
     * <p>供上传会话内的预览与前端回显；文件不是受保护内容时抛出异常，内容缺失时同样抛出异常， 不返回空流。授权依据是无代码侧的上传会话登记，本方法不含普通网盘 ACL。
     *
     * @param fileId 文件内容编号（infra_file.id）
     * @param offset 起始偏移量，0 表示从头读取
     * @return 内容流
     */
    InputStream openTemporaryContent(Long fileId, long offset);

    /**
     * 删除临时上传内容（未进入业务绑定的受保护文件）
     *
     * <p>供无代码侧清理过期上传会话：内容必须带受保护标记且不存在受管文件节点引用；
     * 内容已不存在按删除完成处理，可安全重试。有效保留引用由无代码侧在调用前复核，本方法只做内容身份与节点引用校验。
     *
     * @param fileId 文件内容编号（infra_file.id）
     */
    void deleteTemporaryContent(Long fileId);

    /**
     * 列出受管目录的子节点（业务文件浏览原语）
     *
     * <p>不做网盘 ACL 校验；可见性由无代码侧按业务授权过滤后返回。
     *
     * @param spaceId 业务空间编号
     * @param parentEntryId 父目录节点编号，0 表示空间根
     * @return 子节点列表（目录在前、按名称排序）
     */
    List<DriveBizEntryDTO> listManagedChildren(Long spaceId, Long parentEntryId);

    /**
     * 按名称模糊搜索业务空间内受管节点（业务文件搜索原语）
     *
     * @param spaceId 业务空间编号
     * @param nameLike 名称关键字
     * @param limit 返回上限
     * @return 匹配的受管节点列表
     */
    List<DriveBizEntryDTO> searchManagedEntries(Long spaceId, String nameLike, int limit);
}

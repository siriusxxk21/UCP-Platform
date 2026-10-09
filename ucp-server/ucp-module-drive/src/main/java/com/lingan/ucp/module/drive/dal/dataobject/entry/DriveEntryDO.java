package com.lingan.ucp.module.drive.dal.dataobject.entry;

import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.lingan.ucp.framework.mybatis.core.dataobject.BaseDO;
import com.lingan.ucp.module.drive.enums.entry.DriveEntryTypeEnum;
import com.lingan.ucp.module.drive.enums.entry.DriveTrashStateEnum;

import lombok.*;

import java.time.LocalDateTime;

/**
 * 网盘节点 DO
 *
 * <p>目录与文件都抽象为节点：文件内容在 infra_file，节点只保存展示名称与授权位置， 因此物理路径与展示名可以分离。
 *
 * @author os
 */
@TableName("drive_entry")
@KeySequence("drive_entry_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DriveEntryDO extends BaseDO {

    public static final Long PARENT_ID_ROOT = 0L;

    /** 节点编号 */
    private Long id;

    /** 所属空间编号 */
    private Long spaceId;

    /** 父节点编号，{@link #PARENT_ID_ROOT} 表示空间根目录 */
    private Long parentId;

    /** 节点名称（展示名，不是物理路径） */
    private String name;

    /**
     * 节点类型
     *
     * <p>枚举 {@link DriveEntryTypeEnum}
     */
    private String type;

    /** 文件节点的内容编号，关联 infra_file.id；目录为空 */
    private Long fileId;

    /** 文件大小（字节），目录为 0 */
    private Long size;

    /** 内容 MIME 类型 */
    private String mimeType;

    /**
     * 是否继承上级授权
     *
     * <p>false 时该节点及其子树忽略祖先授权，仅按本级授权判定
     */
    private Boolean inheritParent;

    /**
     * 回收站状态
     *
     * <p>枚举 {@link DriveTrashStateEnum}
     */
    private String trashState;

    /**
     * 是否为业务受管节点
     *
     * <p>true 表示该节点由无代码业务文件规则建立与调整，普通网盘操作（上传、新建目录、
     * 重命名、移动、回收站、分享、继承设置）一律拒绝；受管节点的父节点必然也是受管节点。
     */
    private Boolean managedBiz;

    /** 移入回收站的时间 */
    private LocalDateTime trashedAt;

    /** 移入回收站的操作人 */
    private String trashedBy;

    /** 移入回收站前的父节点，用于恢复时回到原位置 */
    private Long originParentId;

    /** 乐观锁版本，防止移动与重命名并发写坏目录结构 */
    @Version private Integer lockVersion;
}

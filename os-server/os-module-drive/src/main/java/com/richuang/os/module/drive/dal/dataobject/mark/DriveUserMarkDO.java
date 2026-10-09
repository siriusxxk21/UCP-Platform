package com.richuang.os.module.drive.dal.dataobject.mark;

import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableName;
import com.richuang.os.framework.mybatis.core.dataobject.BaseDO;
import com.richuang.os.module.drive.enums.mark.DriveMarkTypeEnum;

import lombok.*;

import java.time.LocalDateTime;

/**
 * 网盘用户标记 DO
 *
 * <p>收藏与最近访问按用户独立，不改变节点授权。
 *
 * @author os
 */
@TableName("drive_user_mark")
@KeySequence("drive_user_mark_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DriveUserMarkDO extends BaseDO {

    /** 编号 */
    private Long id;

    /** 用户编号 */
    private Long userId;

    /** 节点编号 */
    private Long entryId;

    /**
     * 标记类型
     *
     * <p>枚举 {@link DriveMarkTypeEnum}
     */
    private String markType;

    /** 最近访问时间，仅最近访问使用 */
    private LocalDateTime accessTime;
}

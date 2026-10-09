package com.lingan.ucp.module.drive.dal.dataobject.share;

import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableName;
import com.lingan.ucp.framework.mybatis.core.dataobject.BaseDO;
import com.lingan.ucp.module.drive.enums.share.DriveShareStatusEnum;

import lombok.*;

import java.time.LocalDateTime;

/**
 * 网盘分享 DO
 *
 * <p>一条分享对应一个节点与一组接收主体，到期或撤销后不再授予访问。
 *
 * @author os
 */
@TableName("drive_share")
@KeySequence("drive_share_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DriveShareDO extends BaseDO {

    /** 分享编号 */
    private Long id;

    /** 空间编号 */
    private Long spaceId;

    /** 被分享的节点编号 */
    private Long entryId;

    /**
     * 分享授予的角色
     *
     * <p>枚举 {@link com.lingan.ucp.module.drive.enums.permission.DrivePermissionRoleEnum}
     */
    private String role;

    /** 分享失效时间，为空表示长期有效 */
    private LocalDateTime expireTime;

    /**
     * 分享状态
     *
     * <p>枚举 {@link DriveShareStatusEnum}
     */
    private String status;
}

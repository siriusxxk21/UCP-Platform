package com.lingan.ucp.nocode.runtime.dal.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.lingan.ucp.framework.mybatis.core.dataobject.BaseDO;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 业务文件用户标记 DO
 *
 * <p>收藏与最近访问按用户独立保存，不参与任何授权判定：标记只记录用户关注过哪些受管节点， 展示时仍按入口完整授权链重新过滤，失去权限的节点自然不再出现。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "nocode_biz_file_mark", schema = "public")
public class BizFileMarkDO extends BaseDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private String objectId;

    /** 网盘受管节点身份，文件内容仍按业务位置身份重新校验 */
    private Long entryId;

    /** FAVORITE 收藏、RECENT 最近访问 */
    private String markType;

    /** 最近访问时间，仅 RECENT 使用；收藏按登记时间排序 */
    private LocalDateTime accessTime;
}

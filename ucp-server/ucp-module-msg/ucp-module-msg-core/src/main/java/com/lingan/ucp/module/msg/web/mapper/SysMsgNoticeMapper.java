package com.lingan.ucp.module.msg.web.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lingan.ucp.module.msg.web.controller.SysMsgNoticeController.SysMsgNoticeVO;
import com.lingan.ucp.module.msg.web.entity.SysMsgNotice;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface SysMsgNoticeMapper extends BaseMapper<SysMsgNotice> {

    /**
     * 分页查询当前用户的消息通知（联表 sys_msg）
     */
    IPage<SysMsgNoticeVO> selectNoticePage(Page<?> page,
                                           @Param("receiverId") String receiverId,
                                           @Param("msgTemplateId") Long msgTemplateId,
                                           @Param("hasRead") Integer hasRead,
                                           @Param("keyword") String keyword);

    /**
     * 统计当前用户的消息通知总数
     */
    long selectNoticeCount(@Param("receiverId") String receiverId,
                           @Param("msgTemplateId") Long msgTemplateId,
                           @Param("hasRead") Integer hasRead,
                           @Param("keyword") String keyword);

    /**
     * 根据 ID 查询消息通知详情（联表 sys_msg）
     */
    SysMsgNoticeVO selectNoticeById(@Param("messageId") Long messageId,
                                    @Param("receiverId") String receiverId);
}

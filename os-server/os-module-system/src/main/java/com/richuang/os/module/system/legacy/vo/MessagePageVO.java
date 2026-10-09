package com.richuang.os.module.system.legacy.vo;

import lombok.Data;

import java.util.List;

/**
 * 消息分页VO
 */
@Data
public class MessagePageVO {

    private Long total;

    private List<MessageVO> list;
}

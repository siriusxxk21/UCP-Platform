package com.richuang.os.module.system.legacy.service;

import com.richuang.os.module.system.legacy.vo.MessageComponentVO;

import java.util.List;

/**
 * 消息内容组件服务接口
 */
public interface MessageComponentService {

    /**
     * 获取所有可用组件（系统组件 + 租户自定义组件）
     */
    List<MessageComponentVO> listAvailable();

    /**
     * 获取组件详情
     */
    MessageComponentVO getDetail(String id);

    /**
     * 根据编码获取组件
     */
    MessageComponentVO getByCode(String componentCode);

    /**
     * 根据类型获取组件列表
     */
    List<MessageComponentVO> listByType(String componentType);
}

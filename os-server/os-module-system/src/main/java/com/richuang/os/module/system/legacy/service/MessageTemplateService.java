package com.richuang.os.module.system.legacy.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.richuang.os.module.system.legacy.dto.MessageTemplateDTO;
import com.richuang.os.module.system.legacy.dto.MessageTemplateQueryDTO;
import com.richuang.os.module.system.legacy.vo.MessageTemplateVO;

import java.util.List;

/**
 * 消息模板服务接口
 */
public interface MessageTemplateService {

    /**
     * 分页查询模板列表
     */
    Page<MessageTemplateVO> list(MessageTemplateQueryDTO queryDTO);

    /**
     * 获取模板详情
     */
    MessageTemplateVO getDetail(String id);

    /**
     * 根据编码获取模板
     */
    MessageTemplateVO getByCode(String templateCode);

    /**
     * 创建模板
     */
    String create(MessageTemplateDTO dto);

    /**
     * 更新模板
     */
    void update(MessageTemplateDTO dto);

    /**
     * 删除模板
     */
    void delete(String id);

    /**
     * 批量删除模板
     */
    void batchDelete(List<String> ids);

    /**
     * 发布模板
     */
    void publish(String id);

    /**
     * 下架模板
     */
    void unpublish(String id);

    /**
     * 复制模板
     */
    String copy(String id);

    /**
     * 预览模板渲染结果
     */
    MessageTemplateVO preview(String id, Object variables);

    /**
     * 增加使用次数
     */
    void incrementUseCount(String id);

    /**
     * 获取所有已发布模板（下拉选择用）
     */
    List<MessageTemplateVO> listPublished();
}

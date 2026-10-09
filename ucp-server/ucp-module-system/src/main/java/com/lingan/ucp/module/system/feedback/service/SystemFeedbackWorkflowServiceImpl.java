package com.lingan.ucp.module.system.feedback.service;

import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.lingan.ucp.framework.common.pojo.PageResult;
import com.lingan.ucp.framework.security.core.util.SecurityFrameworkUtils;
import com.lingan.ucp.module.infra.dal.dataobject.file.FileDO;
import com.lingan.ucp.module.infra.service.file.FileService;
import com.lingan.ucp.module.system.feedback.dto.*;
import com.lingan.ucp.module.system.feedback.entity.SystemFeedback;
import com.lingan.ucp.module.system.feedback.entity.SystemFeedbackFollowUp;
import com.lingan.ucp.module.system.feedback.enums.FeedbackStatus;
import com.lingan.ucp.module.system.feedback.enums.FeedbackActionType;
import com.lingan.ucp.module.system.feedback.enums.FeedbackType;
import com.lingan.ucp.module.system.feedback.mapper.SystemFeedbackFollowUpMapper;
import com.lingan.ucp.module.system.feedback.mapper.SystemFeedbackMapper;
import jakarta.annotation.Resource;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.Objects;

import static com.lingan.ucp.framework.common.exception.util.ServiceExceptionUtil.invalidParamException;

/** 两态工单管理；主记录与处理记录同一事务保存，不要求指派、审批或提交人确认。 */
@Service
public class SystemFeedbackWorkflowServiceImpl implements SystemFeedbackWorkflowService {
    @Resource
    private SystemFeedbackMapper feedbackMapper;
    @Resource
    private SystemFeedbackFollowUpMapper followUpMapper;
    @Resource
    private FileService fileService;

    @Override
    public PageResult<SystemFeedback> getAdminPage(FeedbackPageReqVO request) {
        if (StrUtil.isNotBlank(request.getStatus())) parseStatus(request.getStatus());
        if (StrUtil.isNotBlank(request.getType())) {
            try { FeedbackType.valueOf(request.getType()); }
            catch (IllegalArgumentException exception) { throw invalidParamException("反馈类型不合法"); }
        }
        QueryWrapper<SystemFeedback> query = new QueryWrapper<>();
        query.eq(StrUtil.isNotBlank(request.getType()), "feedback_type", request.getType())
                .eq(StrUtil.isNotBlank(request.getStatus()), "status", request.getStatus())
                .between(request.getCreateTime() != null && request.getCreateTime().length == 2,
                        "create_time", request.getCreateTime() == null ? null : request.getCreateTime()[0],
                        request.getCreateTime() == null ? null : request.getCreateTime()[1]);
        if (StrUtil.isNotBlank(request.getKeyword())) {
            query.and(item -> item.like("title", request.getKeyword())
                    .or().like("description", request.getKeyword())
                    .or().like("submitter_name", request.getKeyword())
                    .or().like("page_path", request.getKeyword())
                    .or().like("page_title", request.getKeyword())
                    .or().like("feedback_no", request.getKeyword()));
        }
        query.orderByDesc("create_time", "id");
        return feedbackMapper.selectPage(request, query);
    }

    @Override
    public FeedbackDetailRespVO getAdminDetail(Long id) {
        SystemFeedback feedback = requiredFeedback(id);
        FeedbackDetailRespVO result = new FeedbackDetailRespVO();
        BeanUtils.copyProperties(feedback, result);
        List<Long> imageIds = StrUtil.isBlank(feedback.getImageFileIds())
                ? List.of() : JSON.parseArray(feedback.getImageFileIds(), Long.class);
        result.setImageUrls(imageIds.isEmpty() ? List.of()
                : fileService.getFiles(imageIds).stream().map(FileDO::getUrl).filter(Objects::nonNull).toList());
        result.setFollowUps(followUpMapper.selectList(new LambdaQueryWrapper<SystemFeedbackFollowUp>()
                .eq(SystemFeedbackFollowUp::getFeedbackId, id)
                .orderByAsc(SystemFeedbackFollowUp::getCreateTime, SystemFeedbackFollowUp::getId))
                .stream().map(source -> {
                    FeedbackFollowUpRespVO target = new FeedbackFollowUpRespVO();
                    BeanUtils.copyProperties(source, target);
                    target.setFromStatusLabel(statusLabel(source.getFromStatus()));
                    target.setToStatusLabel(statusLabel(source.getToStatus()));
                    return target;
                }).toList());
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void followUp(Long id, FeedbackFollowUpReqVO request) {
        FeedbackStatus status = parseStatus(request.getStatus());
        SystemFeedback feedback = requiredFeedback(id);
        require(Objects.equals(feedback.getVersion(), request.getVersion()), "工单已被其他人更新，请刷新后重试");
        String content = StrUtil.trim(request.getContent());
        require(content == null || content.length() <= 2000, "处理说明不能超过2000字");
        String previous = feedback.getStatus();
        boolean changed = !Objects.equals(previous, status.name());
        require(changed || StrUtil.isNotBlank(content), "请修改解决状态或填写处理说明");
        Long operatorId = SecurityFrameworkUtils.getLoginUserId();
        require(operatorId != null, "请先登录");
        feedback.setStatus(status.name());
        require(feedbackMapper.updateById(feedback) == 1, "工单已被其他人更新，请刷新后重试");
        SystemFeedbackFollowUp record = new SystemFeedbackFollowUp();
        record.setTenantId(feedback.getTenantId());
        record.setFeedbackId(id);
        record.setActionType((changed ? FeedbackActionType.STATUS_CHANGED : FeedbackActionType.COMMENTED).name());
        record.setFromStatus(previous);
        record.setToStatus(status.name());
        record.setOperatorId(operatorId);
        record.setOperatorName(StrUtil.blankToDefault(SecurityFrameworkUtils.getLoginUserNickname(), "管理员"));
        record.setContent(content);
        followUpMapper.insert(record);
    }

    private SystemFeedback requiredFeedback(Long id) {
        SystemFeedback feedback = feedbackMapper.selectById(id);
        require(feedback != null, "工单不存在");
        return feedback;
    }

    private FeedbackStatus parseStatus(String value) {
        try { return FeedbackStatus.valueOf(value == null ? "" : value); }
        catch (IllegalArgumentException exception) { throw invalidParamException("状态只支持未解决或已解决"); }
    }

    /** 历史操作轨迹保留原编码，仅在阅读时归并为两种展示状态。 */
    private String statusLabel(String value) {
        if (StrUtil.isBlank(value)) return null;
        return FeedbackStatus.CLOSED.name().equals(value)
                ? FeedbackStatus.CLOSED.getLabel() : FeedbackStatus.PENDING.getLabel();
    }

    private void require(boolean condition, String message) {
        if (!condition) throw invalidParamException(message);
    }
}

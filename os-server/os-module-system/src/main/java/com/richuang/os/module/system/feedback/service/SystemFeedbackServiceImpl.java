package com.richuang.os.module.system.feedback.service;

import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.richuang.os.framework.security.core.util.SecurityFrameworkUtils;
import com.richuang.os.framework.tenant.core.context.TenantIdResolver;
import com.richuang.os.module.infra.dal.dataobject.file.FileDO;
import com.richuang.os.module.infra.service.file.FileService;
import com.richuang.os.module.infra.service.file.FileConfigService;
import com.richuang.os.module.system.feedback.dto.FeedbackAvailabilityRespVO;
import com.richuang.os.module.system.feedback.dto.FeedbackCreateReqVO;
import com.richuang.os.module.system.feedback.entity.SystemFeedback;
import com.richuang.os.module.system.feedback.entity.SystemFeedbackFollowUp;
import com.richuang.os.module.system.feedback.enums.FeedbackStatus;
import com.richuang.os.module.system.feedback.enums.FeedbackActionType;
import com.richuang.os.module.system.feedback.enums.FeedbackType;
import com.richuang.os.module.system.feedback.mapper.SystemFeedbackFollowUpMapper;
import com.richuang.os.module.system.feedback.mapper.SystemFeedbackMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static com.richuang.os.framework.common.exception.util.ServiceExceptionUtil.invalidParamException;

/** 反馈直接保存为未解决工单，不依赖消息模板或接收人配置。 */
@Slf4j
@Service
public class SystemFeedbackServiceImpl extends ServiceImpl<SystemFeedbackMapper, SystemFeedback>
        implements SystemFeedbackService {
    // 保留历史模板编码供底座注册器兼容；本轮工单不通过消息发送完成提交。
    public static final String MESSAGE_CODE = "SYSTEM_FEEDBACK_SUBMITTED";
    public static final String HANDLER_MESSAGE_CODE = "SYSTEM_FEEDBACK_HANDLER";
    public static final String SUBMITTER_MESSAGE_CODE = "SYSTEM_FEEDBACK_SUBMITTER";

    @Resource
    private FileService fileService;
    @Resource
    private FileConfigService fileConfigService;
    @Resource
    private FeedbackImageValidator imageValidator;
    @Resource
    private TenantIdResolver tenantIdResolver;
    @Resource
    private SystemFeedbackFollowUpMapper followUpMapper;

    @Override
    public FeedbackAvailabilityRespVO getAvailability() {
        return new FeedbackAvailabilityRespVO(true, FeedbackImageValidator.MAX_IMAGES, 5,
                FeedbackImageValidator.ALLOWED_TYPES);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createFeedback(FeedbackCreateReqVO request, String userAgent) {
        Long userId = SecurityFrameworkUtils.getLoginUserId();
        require(userId != null, "请先登录后再提交反馈");
        require(request != null, "反馈内容不能为空");
        FeedbackType type;
        try { type = FeedbackType.valueOf(StrUtil.trim(request.getType())); }
        catch (Exception exception) { throw invalidParamException("反馈类型仅支持Bug或需求"); }
        require(type != FeedbackType.ISSUE, "反馈类型仅支持Bug或需求");
        String title = StrUtil.trim(request.getTitle());
        String description = StrUtil.trim(request.getDescription());
        require(StrUtil.isNotBlank(title) && title.length() <= 100, "请填写100字以内的标题");
        require(StrUtil.isNotBlank(description) && description.length() <= 2000, "请填写2000字以内的反馈描述");
        List<MultipartFile> images = request.getImages() == null ? List.of()
                : Arrays.stream(request.getImages()).filter(item -> item != null && !item.isEmpty()).toList();
        imageValidator.validate(images);
        List<FileDO> uploaded = new ArrayList<>();
        // 文件存储不参与数据库事务；回滚后补偿删除，包括提交阶段失败的情形。
        boolean synchronizedTransaction = TransactionSynchronizationManager.isSynchronizationActive();
        if (synchronizedTransaction) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    if (status == STATUS_ROLLED_BACK) cleanupRolledBackFiles(uploaded);
                }
            });
        }
        try {
            String directory = "feedback/" + LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy/MM"));
            for (MultipartFile image : images) {
                uploaded.add(fileService.createFile(image.getBytes(), image.getOriginalFilename(),
                        directory, image.getContentType()));
            }
            SystemFeedback feedback = new SystemFeedback();
            long id = IdWorker.getId();
            feedback.setId(id);
            feedback.setFeedbackNo("FB-" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE) + "-" + id);
            feedback.setTenantId(tenantIdResolver.getRequiredTenantId());
            feedback.setFeedbackType(type.name());
            feedback.setTitle(title);
            feedback.setDescription(description);
            feedback.setSubmitterId(userId);
            feedback.setSubmitterName(StrUtil.blankToDefault(SecurityFrameworkUtils.getLoginUserNickname(), "未知用户"));
            feedback.setPagePath(truncate(request.getPagePath(), 1000));
            feedback.setPageTitle(truncate(request.getPageTitle(), 200));
            feedback.setBuildCommit(truncate(request.getBuildCommit(), 100));
            feedback.setUserAgent(truncate(userAgent, 500));
            feedback.setImageFileIds(JSON.toJSONString(uploaded.stream().map(FileDO::getId).toList()));
            feedback.setStatus(FeedbackStatus.PENDING.name());
            feedback.setSubmitterRoleNames("[]");
            feedback.setVersion(0);
            require(save(feedback), "反馈保存失败，请重试");
            SystemFeedbackFollowUp record = new SystemFeedbackFollowUp();
            record.setTenantId(feedback.getTenantId());
            record.setFeedbackId(id);
            record.setActionType(FeedbackActionType.CREATED.name());
            record.setToStatus(FeedbackStatus.PENDING.name());
            record.setOperatorId(userId);
            record.setOperatorName(feedback.getSubmitterName());
            record.setContent("提交反馈");
            followUpMapper.insert(record);
            return id;
        } catch (IOException exception) {
            if (!synchronizedTransaction) cleanupFiles(uploaded);
            throw invalidParamException("反馈图片读取失败，请重试");
        } catch (RuntimeException exception) {
            if (!synchronizedTransaction) cleanupFiles(uploaded);
            throw exception;
        }
    }

    private void cleanupFiles(List<FileDO> files) {
        for (FileDO file : files) {
            try { fileService.deleteFile(file.getId()); }
            catch (Exception exception) { log.warn("反馈回滚后图片清理失败: fileId={}", file.getId(), exception); }
        }
    }

    /** 文件记录已随事务回滚，直接通过底座文件客户端清理本次已知路径，不再查询不存在的记录。 */
    private void cleanupRolledBackFiles(List<FileDO> files) {
        for (FileDO file : files) {
            try { fileConfigService.getFileClient(file.getConfigId()).delete(file.getPath()); }
            catch (Exception exception) { log.warn("反馈回滚后存储清理失败: fileId={}", file.getId(), exception); }
        }
    }

    private String truncate(String value, int length) {
        return StrUtil.sub(StrUtil.trim(value), 0, length);
    }

    private void require(boolean condition, String message) {
        if (!condition) throw invalidParamException(message);
    }
}

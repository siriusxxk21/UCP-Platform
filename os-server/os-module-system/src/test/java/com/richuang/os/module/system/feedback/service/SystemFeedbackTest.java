package com.richuang.os.module.system.feedback.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.richuang.os.framework.common.pojo.PageResult;
import com.richuang.os.framework.security.core.util.SecurityFrameworkUtils;
import com.richuang.os.framework.tenant.core.context.TenantIdResolver;
import com.richuang.os.module.infra.dal.dataobject.file.FileDO;
import com.richuang.os.module.infra.service.file.FileService;
import com.richuang.os.module.infra.service.file.FileConfigService;
import com.richuang.os.module.infra.framework.file.core.client.FileClient;
import com.richuang.os.module.system.feedback.dto.*;
import com.richuang.os.module.system.feedback.entity.SystemFeedback;
import com.richuang.os.module.system.feedback.entity.SystemFeedbackFollowUp;
import com.richuang.os.module.system.feedback.mapper.SystemFeedbackFollowUpMapper;
import com.richuang.os.module.system.feedback.mapper.SystemFeedbackMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 验证直接提交、两态直改、并发拒绝、图片补偿及完整管理查询。 */
class SystemFeedbackTest {
    private SystemFeedbackMapper mapper;
    private SystemFeedbackFollowUpMapper history;
    private FileService files;
    private SystemFeedbackServiceImpl submit;
    private SystemFeedbackWorkflowServiceImpl admin;

    @BeforeEach
    void setUp() {
        mapper = mock(SystemFeedbackMapper.class);
        history = mock(SystemFeedbackFollowUpMapper.class);
        files = mock(FileService.class);
        submit = new SystemFeedbackServiceImpl();
        admin = new SystemFeedbackWorkflowServiceImpl();
        ReflectionTestUtils.setField(submit, "baseMapper", mapper);
        ReflectionTestUtils.setField(submit, "followUpMapper", history);
        ReflectionTestUtils.setField(submit, "fileService", files);
        ReflectionTestUtils.setField(submit, "imageValidator", new FeedbackImageValidator());
        TenantIdResolver tenant = mock(TenantIdResolver.class);
        when(tenant.getRequiredTenantId()).thenReturn(0L);
        ReflectionTestUtils.setField(submit, "tenantIdResolver", tenant);
        ReflectionTestUtils.setField(admin, "feedbackMapper", mapper);
        ReflectionTestUtils.setField(admin, "followUpMapper", history);
        ReflectionTestUtils.setField(admin, "fileService", files);
        when(mapper.insert(any(SystemFeedback.class))).thenReturn(1);
        when(mapper.updateById(any(SystemFeedback.class))).thenReturn(1);
    }

    @Test
    void savesWithoutMessageRecipientsOrRoles() {
        try (var security = mockStatic(SecurityFrameworkUtils.class)) {
            security.when(SecurityFrameworkUtils::getLoginUserId).thenReturn(7L);
            security.when(SecurityFrameworkUtils::getLoginUserNickname).thenReturn("反馈用户");
            assertTrue(submit.getAvailability().isEnabled());
            assertNotNull(submit.createFeedback(create(), "test-agent"));
            var capture = ArgumentCaptor.forClass(SystemFeedback.class);
            verify(mapper).insert(capture.capture());
            assertEquals("PENDING", capture.getValue().getStatus());
            assertEquals(7L, capture.getValue().getSubmitterId());
            assertNull(capture.getValue().getAssigneeId());
            verify(history).insert(any(SystemFeedbackFollowUp.class));
        }
    }

    @Test
    void directlyResolvesAndReopensWithoutAssigneeOrSubmitterConfirmation() {
        try (var security = mockStatic(SecurityFrameworkUtils.class)) {
            security.when(SecurityFrameworkUtils::getLoginUserId).thenReturn(9L);
            var feedback = feedback();
            when(mapper.selectById(1L)).thenReturn(feedback);
            admin.followUp(1L, update("CLOSED", 0, null));
            assertEquals("CLOSED", feedback.getStatus());
            admin.followUp(1L, update("PENDING", 0, "继续排查"));
            assertEquals("PENDING", feedback.getStatus());
            verify(history, times(2)).insert(any(SystemFeedbackFollowUp.class));
        }
    }

    @Test
    void rejectsBothStaleVersionAndConcurrentDatabaseUpdate() {
        when(mapper.selectById(1L)).thenReturn(feedback());
        assertThrows(RuntimeException.class, () -> admin.followUp(1L, update("CLOSED", 2, null)));
        verify(mapper, never()).updateById(any(SystemFeedback.class));
        try (var security = mockStatic(SecurityFrameworkUtils.class)) {
            security.when(SecurityFrameworkUtils::getLoginUserId).thenReturn(9L);
            when(mapper.updateById(any(SystemFeedback.class))).thenReturn(0);
            assertThrows(RuntimeException.class, () -> admin.followUp(1L, update("CLOSED", 0, null)));
            verifyNoInteractions(history);
        }
    }

    @Test
    void rejectsFormerWorkflowStatusesAndEmptyChanges() {
        when(mapper.selectById(1L)).thenReturn(feedback());
        for (String status : List.of("PROCESSING", "PENDING_VERIFICATION", "invalid")) {
            assertThrows(RuntimeException.class, () -> admin.followUp(1L, update(status, 0, "说明")));
        }
        assertThrows(RuntimeException.class, () -> admin.followUp(1L, update("PENDING", 0, " ")));
        verify(mapper, never()).updateById(any(SystemFeedback.class));
    }

    @Test
    void rejectsEmptySubmissionBeforeWriting() {
        try (var security = mockStatic(SecurityFrameworkUtils.class)) {
            security.when(SecurityFrameworkUtils::getLoginUserId).thenReturn(7L);
            var request = create();
            request.setTitle(" ");
            assertThrows(RuntimeException.class, () -> submit.createFeedback(request, ""));
            verifyNoInteractions(files, mapper, history);
        }
    }

    @Test
    void cleansEarlierImageIfLaterUploadFails() throws Exception {
        try (var security = mockStatic(SecurityFrameworkUtils.class)) {
            security.when(SecurityFrameworkUtils::getLoginUserId).thenReturn(7L);
            var request = create();
            var png = new MockMultipartFile("images", "a.png", "image/png",
                    new byte[] {(byte) 0x89,0x50,0x4e,0x47,0x0d,0x0a,0x1a,0x0a});
            request.setImages(new MockMultipartFile[] {png, png});
            var file = new FileDO();
            file.setId(8L);
            when(files.createFile(any(), anyString(), anyString(), anyString()))
                    .thenReturn(file).thenThrow(new IllegalStateException("upload failed"));
            assertThrows(RuntimeException.class, () -> submit.createFeedback(request, ""));
            verify(files).deleteFile(8L);
            verifyNoInteractions(mapper, history);
        }
    }

    @Test
    void cleansStorageAfterTransactionRollbackWithoutReadingRolledBackFileRows() throws Exception {
        try (var security = mockStatic(SecurityFrameworkUtils.class)) {
            security.when(SecurityFrameworkUtils::getLoginUserId).thenReturn(7L);
            var config = mock(FileConfigService.class);
            var client = mock(FileClient.class);
            when(config.getFileClient(3L)).thenReturn(client);
            ReflectionTestUtils.setField(submit, "fileConfigService", config);
            var request = create();
            request.setImages(new MockMultipartFile[] {new MockMultipartFile("images", "a.png", "image/png",
                    new byte[] {(byte) 0x89,0x50,0x4e,0x47,0x0d,0x0a,0x1a,0x0a})});
            var file = new FileDO().setConfigId(3L).setPath("feedback/owned.png");
            file.setId(8L);
            when(files.createFile(any(), anyString(), anyString(), anyString())).thenReturn(file);
            TransactionSynchronizationManager.initSynchronization();
            try {
                submit.createFeedback(request, "");
                TransactionSynchronizationManager.getSynchronizations().forEach(sync ->
                        sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
                verify(client).delete("feedback/owned.png");
                verify(files, never()).deleteFile(anyLong());
            } finally { TransactionSynchronizationManager.clearSynchronization(); }
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void queriesAllSubmittersAndSearchesTitle() {
        FeedbackPageReqVO query = new FeedbackPageReqVO();
        query.setKeyword("保存失败");
        query.setStatus("PENDING");
        when(mapper.selectPage(eq(query), any(QueryWrapper.class))).thenReturn(new PageResult<>(List.of(), 0L));
        admin.getAdminPage(query);
        var capture = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(mapper).selectPage(eq(query), capture.capture());
        String sql = capture.getValue().getSqlSegment();
        assertTrue(sql.contains("title LIKE"));
        assertFalse(sql.contains("submitter_id"));
    }

    private FeedbackCreateReqVO create() {
        var request = new FeedbackCreateReqVO();
        request.setType("BUG");
        request.setTitle("保存失败");
        request.setDescription("填写表单后点击保存报错");
        request.setPagePath("/nocode-app/runtime?id=940");
        return request;
    }

    private SystemFeedback feedback() {
        var feedback = new SystemFeedback();
        feedback.setId(1L);
        feedback.setStatus("PENDING");
        feedback.setVersion(0);
        feedback.setTenantId(0L);
        return feedback;
    }

    private FeedbackFollowUpReqVO update(String status, int version, String content) {
        var request = new FeedbackFollowUpReqVO();
        request.setStatus(status);
        request.setVersion(version);
        request.setContent(content);
        return request;
    }
}

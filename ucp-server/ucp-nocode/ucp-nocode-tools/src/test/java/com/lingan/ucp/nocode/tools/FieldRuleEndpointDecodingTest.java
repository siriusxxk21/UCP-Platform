package com.lingan.ucp.nocode.tools;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lingan.ucp.framework.common.exception.ServiceException;
import com.lingan.ucp.nocode.api.*;
import com.lingan.ucp.nocode.runtime.service.record.RecordService;
import com.lingan.ucp.nocode.runtime.service.task.TaskEntryRuntimeService;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.*;

/** 求值端点的请求边界（不连库）：规则配置只从服务端固定版本读取，请求体带任何配置直接拒绝（设计稿 8 章 B37）。 */
class FieldRuleEndpointDecodingTest {
    @Test
    void taskCenterHelpersRejectCallerSuppliedRulesAndVersions() throws Exception {
        ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        com.lingan.ucp.nocode.web.StrictRequestDecoder decoder =
                new com.lingan.ucp.nocode.web.StrictRequestDecoder();
        ReflectionTestUtils.setField(decoder, "json", json);
        ReflectionTestUtils.invokeMethod(decoder, "initialize");
        com.lingan.ucp.nocode.runtime.service.taskcenter.TaskWorkEntryService service =
                Mockito.mock(
                        com.lingan.ucp.nocode.runtime.service.taskcenter.TaskWorkEntryService
                                .class);
        com.lingan.ucp.nocode.web.NocodeAccess access =
                Mockito.mock(com.lingan.ucp.nocode.web.NocodeAccess.class);
        Mockito.when(access.actor()).thenReturn(20002L);
        com.lingan.ucp.nocode.controller.admin.task.TaskWorkEntryController controller =
                new com.lingan.ucp.nocode.controller.admin.task.TaskWorkEntryController();
        ReflectionTestUtils.setField(controller, "service", service);
        ReflectionTestUtils.setField(controller, "requests", decoder);
        ReflectionTestUtils.setField(controller, "access", access);
        for (String extra : List.of("\"rules\":{}", "\"version\":9", "\"actorId\":1")) {
            assertThatThrownBy(
                            () ->
                                    controller.fieldRules(
                                            json.readTree(
                                                    "{\"target\":{\"taskId\":\"1\",\"entryKey\":\"work\"},\"query\":{\"applicationId\":\"2\",\"objectId\":\"3\","
                                                            + extra
                                                            + "}}")))
                    .isInstanceOf(ServiceException.class);
        }
        Mockito.verifyNoInteractions(service);
        controller.fieldRules(
                json.readTree(
                        "{\"target\":{\"taskId\":\"1\",\"entryKey\":\"work\"},\"query\":{\"applicationId\":\"2\",\"objectId\":\"3\",\"formId\":\"form\",\"values\":{}}}"));
        Mockito.verify(service).fieldRules(Mockito.any(), Mockito.eq(20002L));
    }

    /** B37：配置只从服务端固定版本读取；请求体带配置（未知属性）直接拒绝，服务不被调用。 */
    @Test
    void rejectsConfigInBody() throws Exception {
        var json = new ObjectMapper().findAndRegisterModules();
        var decoder = new com.lingan.ucp.nocode.web.StrictRequestDecoder();
        ReflectionTestUtils.setField(decoder, "json", json);
        ReflectionTestUtils.invokeMethod(decoder, "initialize");
        var records = Mockito.mock(RecordService.class);
        Mockito.when(records.evaluateRules(Mockito.any(), Mockito.anyLong()))
                .thenReturn(new FieldRules.Evaluation(List.of()));
        var access = Mockito.mock(com.lingan.ucp.nocode.web.NocodeAccess.class);
        Mockito.when(access.actor()).thenReturn(10001L);
        var controller = new com.lingan.ucp.nocode.controller.admin.ApplicationRuntimeController();
        ReflectionTestUtils.setField(controller, "records", records);
        ReflectionTestUtils.setField(controller, "requests", decoder);
        ReflectionTestUtils.setField(controller, "access", access);
        for (String body :
                List.of(
                        "{\"applicationId\":\"1\",\"objectId\":\"2\",\"values\":{},\"linkage\":{\"sourceObjectId\":\"9\"}}",
                        "{\"applicationId\":\"1\",\"objectId\":\"2\",\"rules\":{\"defaultFormula\":\"1\"}}",
                        "{\"applicationId\":\"1\",\"objectId\":\"2\",\"details\":[{\"detailId\":\"3\",\"rows\":[{\"rowKey\":\"k\",\"conditions\":[]}]}]}"))
            assertThatThrownBy(() -> controller.evaluateFieldRules(json.readTree(body)))
                    .isInstanceOf(ServiceException.class)
                    .hasMessageContaining("运行请求结构无效");
        Mockito.verify(records, Mockito.never()).evaluateRules(Mockito.any(), Mockito.anyLong());
        controller.evaluateFieldRules(
                json.readTree(
                        "{\"applicationId\":\"1\",\"objectId\":\"2\",\"values\":{\"30011\":\"88001\"},\"changed\":[\"30011\"]}"));
        Mockito.verify(records).evaluateRules(Mockito.any(), Mockito.eq(10001L));
        var entries = Mockito.mock(TaskEntryRuntimeService.class);
        var task = new com.lingan.ucp.nocode.controller.admin.task.TaskEntryController();
        ReflectionTestUtils.setField(task, "runtime", entries);
        ReflectionTestUtils.setField(task, "requests", decoder);
        ReflectionTestUtils.setField(task, "access", access);
        assertThatThrownBy(
                        () ->
                                task.fieldRules(
                                        json.readTree(
                                                "{\"entry\":{\"applicationId\":\"1\",\"entryId\":\"e\"},\"query\":{\"applicationId\":\"1\",\"objectId\":\"2\",\"linkage\":{}}}")))
                .hasMessageContaining("运行请求结构无效");
        Mockito.verify(entries, Mockito.never()).fieldRules(Mockito.any(), Mockito.anyLong());
    }
}

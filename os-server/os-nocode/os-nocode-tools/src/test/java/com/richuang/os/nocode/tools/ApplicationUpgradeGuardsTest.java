package com.richuang.os.nocode.tools;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.hutool.crypto.digest.DigestUtil;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.richuang.os.framework.common.biz.system.permission.PermissionCommonApi;
import com.richuang.os.module.bpm.api.definition.BpmProcessDefinitionApi;
import com.richuang.os.module.bpm.api.definition.dto.BpmBusinessBindingDTO;
import com.richuang.os.nocode.api.ApplicationCenter;
import com.richuang.os.nocode.api.ObjectApplicationUpgrade;
import com.richuang.os.nocode.application.dal.dataobject.NocodeApplicationDO;
import com.richuang.os.nocode.application.dal.dataobject.NocodeApplicationVersionDO;
import com.richuang.os.nocode.application.dal.dataobject.NocodeRecordProcessDO;
import com.richuang.os.nocode.application.dal.mapper.ApplicationMapper;
import com.richuang.os.nocode.application.dal.mapper.RecordProcessMapper;
import com.richuang.os.nocode.application.service.application.ApplicationService;
import com.richuang.os.nocode.application.service.application.ApplicationUpgradeServiceImpl;
import com.richuang.os.nocode.application.service.resource.ApplicationAutomationCatalog;
import com.richuang.os.nocode.application.service.resource.ApplicationResourceValidator;
import com.richuang.os.nocode.metadata.service.object.DraftValidator;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

/** 维护暂停的硬阻断在写状态前验证；无需写入开发库即可覆盖流程和自动更新边界。 */
class ApplicationUpgradeGuardsTest {
    private ApplicationUpgradeServiceImpl service;
    private ApplicationMapper store;
    private RecordProcessMapper processes;
    private BpmProcessDefinitionApi definitions;
    private PermissionCommonApi permissions;
    private ObjectMapper json;

    @BeforeEach
    void setup() throws Exception {
        service = new ApplicationUpgradeServiceImpl();
        store = mock(ApplicationMapper.class);
        processes = mock(RecordProcessMapper.class);
        definitions = mock(BpmProcessDefinitionApi.class);
        permissions = mock(PermissionCommonApi.class);
        ApplicationService applications = mock(ApplicationService.class);
        DraftValidator validator = mock(DraftValidator.class);
        json = new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
        ReflectionTestUtils.setField(service, "store", store);
        ReflectionTestUtils.setField(service, "processes", processes);
        ReflectionTestUtils.setField(service, "processDefinitions", definitions);
        ReflectionTestUtils.setField(service, "permissions", permissions);
        ReflectionTestUtils.setField(service, "applications", applications);
        ReflectionTestUtils.setField(service, "validator", validator);
        ReflectionTestUtils.setField(service, "json", json);
        when(permissions.hasAnyPermissions(10001L, "nocode:app:manage")).thenReturn(true);
        when(validator.id("1", "应用 ID")).thenReturn(1L);
        NocodeApplicationDO app = new NocodeApplicationDO();
        app.setId(1L);
        app.setAppName("维护测试应用");
        app.setStatus("ACTIVE");
        app.setLockVersion(2);
        app.setPublishedVersion(1);
        when(store.lock(1L, true)).thenReturn(app);
        when(processes.active(1L)).thenReturn(List.of());
        when(definitions.getEffectiveBusinessBindings("nocode")).thenReturn(List.of());
        version(List.of());
    }

    @Test
    void deployedBusinessBindingBlocksPauseEvenWithoutRunningRecord() {
        when(definitions.getEffectiveBusinessBindings("nocode"))
                .thenReturn(
                        List.of(
                                new BpmBusinessBindingDTO(
                                        "process-1",
                                        "采购审批",
                                        3,
                                        "review",
                                        "处理采购",
                                        "{\"resource\":{\"applicationId\":\"1\"}}")));
        assertBlocked();
    }

    @Test
    void pendingHandlingAndRunningProcessBlockPause() {
        when(store.unresolvedHandling(eq(1L), anyList())).thenReturn(1L);
        assertBlocked();
        when(store.unresolvedHandling(eq(1L), anyList())).thenReturn(0L);
        when(processes.active(1L)).thenReturn(List.of(new NocodeRecordProcessDO()));
        assertBlocked();
    }

    @Test
    void enabledAutomationBlocksWholeApplicationPause() throws Exception {
        version(
                List.of(
                        new ApplicationCenter.Resource(
                                "rule-1",
                                "AUTOMATION",
                                "rule",
                                "库存自动更新",
                                Map.of("enabled", true))));
        assertBlocked();
    }

    @Test
    void enablingChecksOlderApplicationVersionPinnedByDeployedFlow() throws Exception {
        ApplicationService application = new ApplicationService();
        ReflectionTestUtils.setField(application, "store", store);
        ReflectionTestUtils.setField(application, "processDefinitions", definitions);
        ReflectionTestUtils.setField(application, "json", json);
        ReflectionTestUtils.setField(
                application, "automations", mock(ApplicationAutomationCatalog.class));
        ApplicationResourceValidator resourcesValidator = mock(ApplicationResourceValidator.class);
        when(resourcesValidator.normalize(anyList(), anyList())).thenReturn(List.of());
        ReflectionTestUtils.setField(application, "resourcesValidator", resourcesValidator);
        NocodeApplicationDO app = new NocodeApplicationDO();
        app.setId(1L);
        app.setAppName("维护测试应用");
        ApplicationCenter.Snapshot oldSnapshot =
                new ApplicationCenter.Snapshot(
                        "app", "维护测试应用", null, null, ApplicationCenter.Definition.empty());
        String encoded = json.writeValueAsString(oldSnapshot);
        String checksum = DigestUtil.sha256Hex(encoded);
        NocodeApplicationVersionDO oldRelease = new NocodeApplicationVersionDO();
        oldRelease.setDefinitionJson(encoded);
        oldRelease.setChecksum(checksum);
        when(store.version(1L, 2)).thenReturn(oldRelease);
        when(definitions.getEffectiveBusinessBindings("nocode"))
                .thenReturn(
                        List.of(
                                new BpmBusinessBindingDTO(
                                        "process-old",
                                        "采购审批",
                                        3,
                                        "review",
                                        "处理采购",
                                        "{\"resource\":{\"applicationId\":\"1\",\"applicationVersion\":2,\"applicationChecksum\":\""
                                                + checksum
                                                + "\"}}")));
        assertThatThrownBy(
                        () ->
                                ReflectionTestUtils.invokeMethod(
                                        application, "validateEffectiveFlowBindings", app, 1))
                .hasMessageContaining("采购审批")
                .hasMessageContaining("应用 V2")
                .hasMessageContaining("已不兼容");
    }

    private void assertBlocked() {
        ObjectApplicationUpgrade.Impact impact =
                new ObjectApplicationUpgrade.Impact(
                        "1",
                        "维护测试应用",
                        2,
                        1,
                        1,
                        List.of("字段不兼容"),
                        List.of(),
                        "/nocode-app/workspace?id=1");
        assertThatThrownBy(() -> service.suspend(List.of(impact), 10001L, "结构升级"))
                .hasMessageContaining("不能暂停");
        verify(store, never()).status(anyLong(), anyString(), anyString());
    }

    private void version(List<ApplicationCenter.Resource> resources) throws Exception {
        ApplicationCenter.Snapshot snapshot =
                new ApplicationCenter.Snapshot(
                        "app",
                        "维护测试应用",
                        null,
                        null,
                        new ApplicationCenter.Definition(List.of(), resources));
        String encoded = json.writeValueAsString(snapshot);
        NocodeApplicationVersionDO release = new NocodeApplicationVersionDO();
        release.setDefinitionJson(encoded);
        release.setChecksum(DigestUtil.sha256Hex(encoded));
        when(store.version(1L, 1)).thenReturn(release);
    }
}

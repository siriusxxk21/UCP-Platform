package com.richuang.os.nocode.tools;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.richuang.os.nocode.api.*;
import com.richuang.os.nocode.api.DataCenter.*;
import com.richuang.os.nocode.api.FieldConversionDependencyInspector.Impact;
import com.richuang.os.nocode.application.dal.dataobject.*;
import com.richuang.os.nocode.application.dal.mapper.*;
import com.richuang.os.nocode.application.service.resource.ApplicationFieldConversionDependencies;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.*;

/** 授权条件来自独立持久化记录，不能仅扫描应用快照或把普通字段授权视为类型依赖。 */
class ApplicationFieldConversionAuthorizationTest {
    private final ObjectMapper json = new ObjectMapper();
    private final ApplicationMapper applications = mock(ApplicationMapper.class);
    private final ApplicationAccessMapper access = mock(ApplicationAccessMapper.class);
    private final TaskEntryAccessMapper entries = mock(TaskEntryAccessMapper.class);
    private final ObjectApplicationGrantMapper sharing = mock(ObjectApplicationGrantMapper.class);
    private final DataObjectApi objects = mock(DataObjectApi.class);
    private final ApplicationFieldConversionDependencies inspector =
            new ApplicationFieldConversionDependencies();
    private final Definition previous = definition("TEXT");
    private final Definition proposed = definition("INTEGER");

    @BeforeEach
    void setup() throws Exception {
        ReflectionTestUtils.setField(inspector, "applications", applications);
        ReflectionTestUtils.setField(inspector, "access", access);
        ReflectionTestUtils.setField(inspector, "entryAccess", entries);
        ReflectionTestUtils.setField(inspector, "sharing", sharing);
        ReflectionTestUtils.setField(inspector, "objects", objects);
        ReflectionTestUtils.setField(inspector, "json", json);
        NocodeApplicationDO application = new NocodeApplicationDO();
        application.setId(10L);
        application.setAppName("业务应用");
        application.setPublishedVersion(1);
        when(applications.runnableIds()).thenReturn(List.of("10"));
        when(applications.selectById(10L)).thenReturn(application);
        ApplicationCenter.Definition definition =
                new ApplicationCenter.Definition(
                        List.of(new ApplicationCenter.ObjectReference("1", 1, "checksum")),
                        List.of(
                                new ApplicationCenter.Resource(
                                        "entry",
                                        "TASK_ENTRY",
                                        "entry",
                                        "采购办理入口",
                                        Map.of("objectId", "1", "limits", List.of()))));
        NocodeApplicationVersionDO version = new NocodeApplicationVersionDO();
        version.setDefinitionJson(
                json.writeValueAsString(
                        new ApplicationCenter.Snapshot("app", "业务应用", null, null, definition)));
        when(applications.version(10L, 1)).thenReturn(version);
        when(objects.getVersion("1", 1))
                .thenReturn(new DataObjectApi.PublishedObject("1", 1, "checksum", previous));
        when(sharing.forApplication(10L)).thenReturn(List.of());
    }

    @Test
    void activeEntryMembersAndSharedObjectScopesReportPreciseLiveAuthorizationLocations()
            throws Exception {
        ApplicationAuthorization.ObjectGrant grant =
                grant(
                        new DataScope(
                                "AND",
                                List.of(new DataScope.Condition("f", "eq", "旧编码")),
                                List.of()));
        when(entries.find(10L, "entry")).thenReturn(entry(true, grant));
        when(sharing.forApplication(10L)).thenReturn(List.of(shared(grant)));
        List<Impact> impacts = inspector.inspect(previous, proposed, Set.of("f"));
        assertThat(impacts).hasSize(2).allMatch(i -> i.blocking() && i.fieldId().equals("f"));
        assertThat(impacts).anyMatch(i -> i.location().contains("采购办理入口 / 成员授权 / USER 7"));
        assertThat(impacts).anyMatch(i -> i.location().contains("对象共享授权 / 1"));
        assertThat(impacts)
                .allMatch(i -> i.message().contains("保存授权") && !i.message().contains("发布应用"));
        assertThat(impacts).allMatch(i -> i.route().equals("/nocode-app/workspace?id=10"));
    }

    @Test
    void disabledEntryAndRevokedSharedGrantDoNotBlockConversion() throws Exception {
        ApplicationAuthorization.ObjectGrant grant =
                grant(
                        new DataScope(
                                "AND",
                                List.of(new DataScope.Condition("f", "eq", "旧编码")),
                                List.of()));
        when(entries.find(10L, "entry")).thenReturn(entry(false, grant));
        when(sharing.forApplication(10L)).thenReturn(List.of(shared(null)));
        assertThat(inspector.inspect(previous, proposed, Set.of("f"))).isEmpty();
    }

    @Test
    void ordinaryFieldPermissionsAndTypeIndependentNullScopesRemainCompatible() throws Exception {
        when(entries.find(10L, "entry")).thenReturn(entry(true, grant(null)));
        when(sharing.forApplication(10L))
                .thenReturn(
                        List.of(
                                shared(
                                        grant(
                                                new DataScope(
                                                        "AND",
                                                        List.of(
                                                                new DataScope.Condition(
                                                                        "f", "isNull", null)),
                                                        List.of())))));
        assertThat(inspector.inspect(previous, proposed, Set.of("f"))).isEmpty();
    }

    private TaskEntryAccessDO entry(boolean enabled, ApplicationAuthorization.ObjectGrant grant)
            throws Exception {
        TaskEntryAccessDO policy = new TaskEntryAccessDO();
        policy.setEnabled(enabled);
        policy.setPolicyJson(
                json.writeValueAsString(
                        List.of(new ApplicationAuthorization.Member("USER", "7", List.of(grant)))));
        return policy;
    }

    private NocodeObjectApplicationGrantDO shared(ApplicationAuthorization.ObjectGrant grant)
            throws Exception {
        NocodeObjectApplicationGrantDO shared = new NocodeObjectApplicationGrantDO();
        shared.setGrantJson(json.writeValueAsString(grant));
        return shared;
    }

    private ApplicationAuthorization.ObjectGrant grant(DataScope scope) {
        return new ApplicationAuthorization.ObjectGrant(
                "1",
                Set.of("READ", "UPDATE"),
                "ALL",
                Set.of("f"),
                Set.of("f"),
                Set.of(),
                Set.of(),
                Set.of(),
                Set.of(),
                scope == null ? Map.of() : Map.of("READ", scope),
                Set.of());
    }

    private Definition definition(String type) {
        FieldDefinition field =
                new FieldDefinition(
                        "f",
                        "f",
                        "value",
                        "原字段",
                        type,
                        "TEXT".equals(type) ? 200 : null,
                        null,
                        null,
                        false,
                        false,
                        0);
        return new Definition(
                "1",
                "object",
                "业务对象",
                null,
                "public",
                "biz_object",
                "GENERATED",
                false,
                "f",
                Settings.defaults(),
                List.of(field),
                Map.of(),
                List.of(),
                List.of(),
                List.of());
    }
}

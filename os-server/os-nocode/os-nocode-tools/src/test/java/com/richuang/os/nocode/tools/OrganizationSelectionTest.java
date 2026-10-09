package com.richuang.os.nocode.tools;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.*;

import com.richuang.os.framework.common.biz.system.dict.dto.DictDataRespDTO;
import com.richuang.os.module.system.api.dict.DictDataApi;
import com.richuang.os.module.system.api.organization.OrganizationApi;
import com.richuang.os.module.system.api.organization.OrganizationApiImpl;
import com.richuang.os.module.system.dal.dataobject.organization.OrganizationDO;
import com.richuang.os.module.system.dal.mysql.organization.OrganizationMapper;
import com.richuang.os.module.system.service.organization.OrganizationService;
import com.richuang.os.module.system.service.organization.OrganizationServiceImpl;
import com.richuang.os.nocode.api.DataCenter;
import com.richuang.os.nocode.api.FieldDefinition;
import com.richuang.os.nocode.api.SelectionFields;
import com.richuang.os.nocode.runtime.service.record.RuntimeSchema;
import com.richuang.os.nocode.runtime.service.selection.SelectionCatalog;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.*;

/** 使用真实组织树构建、底座校验及候选服务；仅替换数据库 Mapper，锁定目录间不同的状态编码。 */
class OrganizationSelectionTest {
    private final FieldDefinition field =
            new FieldDefinition(
                    "org",
                    "org",
                    "belong_org",
                    "所属组织",
                    "ORGANIZATION",
                    null,
                    null,
                    null,
                    false,
                    false,
                    0);
    private final List<OrganizationDO> records = new ArrayList<>();
    private SelectionCatalog catalog;
    private OrganizationApi api;
    private DefaultListableBeanFactory beans;

    @BeforeEach
    void setup() {
        var mapper = mock(OrganizationMapper.class);
        when(mapper.selectListAll()).thenAnswer(call -> List.copyOf(records));
        when(mapper.selectListByIds(anyCollection()))
                .thenAnswer(
                        call -> {
                            Collection<Long> ids = call.getArgument(0);
                            return records.stream().filter(o -> ids.contains(o.getId())).toList();
                        });
        var service = new OrganizationServiceImpl();
        ReflectionTestUtils.setField(service, "organizationMapper", mapper);
        api = new OrganizationApiImpl();
        ReflectionTestUtils.setField(api, "organizationService", service);
        beans = new DefaultListableBeanFactory();
        beans.registerSingleton("organizations", service);
        beans.registerSingleton("organizationApi", api);
        catalog = new SelectionCatalog();
        ReflectionTestUtils.setField(
                catalog, "organizations", beans.getBeanProvider(OrganizationService.class));
        ReflectionTestUtils.setField(
                catalog, "organizationApiProvider", beans.getBeanProvider(OrganizationApi.class));
    }

    @Test
    void enabledOrganizationsAreSelectableAndPassTheActualSaveValidator() {
        // 数值直接对应现有数据库与管理界面的契约，避免夹具随错误枚举一起反转。
        organization(101L, "LY", 0L, 2, 1);
        organization(102L, "LA", 101L, 2, 1);
        var options = options(List.of("101"), true, List.of(1, 2, 3));
        var choices = catalog.options(field, options);
        assertThat(choices).extracting(SelectionFields.Option::value).containsExactly("101", "102");
        assertThat(choices).noneMatch(SelectionFields.Option::disabled);
        assertThat(choices.getLast().path()).isEqualTo("LY / LA");
        for (String id : List.of("101", "102")) {
            assertThatCode(
                            () ->
                                    catalog.validate(
                                            table(options), Map.of("belong_org", id), Map.of()))
                    .doesNotThrowAnyException();
        }
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(ints = {0, 2})
    void disabledOrInvalidStatusCannotBecomeANewSelection(Integer status) {
        organization(101L, "停用组织", 0L, 2, status);
        var options = options(List.of(), true, List.of());
        assertThat(catalog.options(field, options))
                .singleElement()
                .satisfies(o -> assertThat(o.disabled()).isTrue());
        assertThatThrownBy(
                        () ->
                                catalog.validate(
                                        table(options), Map.of("belong_org", "101"), Map.of()))
                .hasMessageContaining("已停用");
        assertThatThrownBy(() -> api.validateOrganizationList(List.of(101L)))
                .hasMessageContaining("不处于开启状态");
        // 历史引用未变时仍允许保存其他字段。
        assertThatCode(
                        () ->
                                catalog.validate(
                                        table(options),
                                        Map.of("belong_org", "101"),
                                        Map.of("org", "101")))
                .doesNotThrowAnyException();
    }

    @Test
    void enabledStatusDoesNotBypassRootDescendantOrTypeRestrictions() {
        organization(101L, "总部", 0L, 1, 1);
        organization(102L, "分公司", 101L, 2, 1);
        organization(103L, "范围外公司", 0L, 2, 1);
        var options = options(List.of("101"), true, List.of(2));
        var choices = catalog.options(field, options);
        assertThat(choices).extracting(SelectionFields.Option::value).containsExactly("101", "102");
        assertThat(choices.getFirst().disabled()).isTrue();
        assertThat(choices.getLast().disabled()).isFalse();
        for (String id : List.of("101", "103")) {
            assertThatThrownBy(
                            () ->
                                    catalog.validate(
                                            table(options), Map.of("belong_org", id), Map.of()))
                    .hasMessageContaining("超出允许范围");
        }
        assertThat(catalog.options(field, options(List.of("101"), false, List.of())))
                .extracting(SelectionFields.Option::value)
                .containsExactly("101");
    }

    @Test
    void missingOrganizationsAreStillRejectedByTheFoundationApi() {
        assertThatThrownBy(() -> api.validateOrganizationList(List.of(404L)))
                .hasMessageContaining("不存在");
    }

    @Test
    void dictionaryStatusRetainsTheCommonZeroEnabledConvention() {
        var dictionaries = mock(DictDataApi.class);
        var enabled = new DictDataRespDTO();
        enabled.setValue("ACTIVE");
        enabled.setLabel("启用项");
        enabled.setStatus(0);
        var disabled = new DictDataRespDTO();
        disabled.setValue("INACTIVE");
        disabled.setLabel("停用项");
        disabled.setStatus(1);
        when(dictionaries.getDictDataList("status_test")).thenReturn(List.of(enabled, disabled));
        beans.registerSingleton("dictionaries", dictionaries);
        ReflectionTestUtils.setField(
                catalog, "dictionaries", beans.getBeanProvider(DictDataApi.class));
        var options =
                DataCenter.FieldOptions.defaults()
                        .withSelection(
                                new SelectionFields.Source(
                                        "SYSTEM_DICTIONARY",
                                        null,
                                        "status_test",
                                        List.of(),
                                        false,
                                        List.of(),
                                        "NONE"));
        assertThat(catalog.options(field, options))
                .extracting(SelectionFields.Option::disabled)
                .containsExactly(false, true);
    }

    @Test
    void postsLoadAllCandidatesThroughTheFoundationService() {
        var mapper = mock(com.richuang.os.module.system.dal.mysql.dept.PostMapper.class);
        var enabled = new com.richuang.os.module.system.dal.dataobject.dept.PostDO();
        enabled.setId(201L);
        enabled.setName("项目经理");
        enabled.setCode("manager");
        enabled.setStatus(0);
        var disabled = new com.richuang.os.module.system.dal.dataobject.dept.PostDO();
        disabled.setId(202L);
        disabled.setName("停用岗位");
        disabled.setCode("disabled");
        disabled.setStatus(1);
        when(mapper.selectList((Collection<Long>) null, (Collection<Integer>) null))
                .thenReturn(List.of(enabled, disabled));
        var service = new com.richuang.os.module.system.service.dept.PostServiceImpl();
        ReflectionTestUtils.setField(service, "postMapper", mapper);
        beans.registerSingleton("posts", service);
        ReflectionTestUtils.setField(
                catalog,
                "posts",
                beans.getBeanProvider(
                        com.richuang.os.module.system.service.dept.PostService.class));
        var options =
                DataCenter.FieldOptions.defaults()
                        .withSelection(
                                new SelectionFields.Source(
                                        "DIRECTORY",
                                        "POST",
                                        null,
                                        List.of(),
                                        false,
                                        List.of(),
                                        "NONE"));
        assertThat(catalog.options(field, options))
                .extracting(SelectionFields.Option::value)
                .containsExactly("201", "202");
        assertThat(catalog.options(field, options))
                .extracting(SelectionFields.Option::disabled)
                .containsExactly(false, true);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(ints = {0, 1})
    void userGroupStatusIsOptionalAndCandidatesKeepDisabledLabels(Integer status) {
        var type = com.richuang.os.module.bpm.dal.dataobject.definition.BpmUserGroupDO.class;
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(),
                        "user-group-test"),
                type);
        var enabled = new com.richuang.os.module.bpm.dal.dataobject.definition.BpmUserGroupDO();
        enabled.setId(301L);
        enabled.setName("启用用户组");
        enabled.setStatus(0);
        var disabled = new com.richuang.os.module.bpm.dal.dataobject.definition.BpmUserGroupDO();
        disabled.setId(302L);
        disabled.setName("停用用户组");
        disabled.setStatus(1);
        var all = List.of(enabled, disabled);
        var mapper =
                mock(
                        com.richuang.os.module.bpm.dal.mysql.definition.BpmUserGroupMapper.class,
                        CALLS_REAL_METHODS);
        doAnswer(
                        call -> {
                            com.baomidou.mybatisplus.core.conditions.AbstractWrapper<?, ?, ?>
                                    query = call.getArgument(0);
                            if (status == null) {
                                assertThat(query.getSqlSegment()).isEmpty();
                                assertThat(query.getParamNameValuePairs()).isEmpty();
                                return all;
                            }
                            assertThat(query.getSqlSegment()).contains("status");
                            assertThat(query.getParamNameValuePairs().values())
                                    .containsExactly(status);
                            return all.stream().filter(g -> status.equals(g.getStatus())).toList();
                        })
                .when(mapper)
                .selectList(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class));
        var service = new com.richuang.os.module.bpm.service.definition.BpmUserGroupServiceImpl();
        ReflectionTestUtils.setField(service, "userGroupMapper", mapper);
        if (status != null) {
            assertThat(service.getUserGroupListByStatus(status))
                    .singleElement()
                    .satisfies(g -> assertThat(g.getStatus()).isEqualTo(status));
            return;
        }
        var groupApi = new com.richuang.os.module.bpm.api.definition.BpmUserGroupApiImpl();
        ReflectionTestUtils.setField(groupApi, "userGroups", service);
        beans.registerSingleton("groups", groupApi);
        ReflectionTestUtils.setField(
                catalog,
                "groups",
                beans.getBeanProvider(
                        com.richuang.os.module.bpm.api.definition.BpmUserGroupApi.class));
        var options =
                DataCenter.FieldOptions.defaults()
                        .withSelection(
                                new SelectionFields.Source(
                                        "DIRECTORY",
                                        "USER_GROUP",
                                        null,
                                        List.of(),
                                        false,
                                        List.of(),
                                        "NONE"));
        assertThat(catalog.options(field, options))
                .extracting(SelectionFields.Option::value)
                .containsExactly("301", "302");
        assertThat(catalog.options(field, options))
                .extracting(SelectionFields.Option::disabled)
                .containsExactly(false, true);
    }

    private DataCenter.FieldOptions options(
            List<String> roots, boolean descendants, List<Integer> types) {
        return DataCenter.FieldOptions.defaults()
                .withSelection(
                        new SelectionFields.Source(
                                "DIRECTORY",
                                "ORGANIZATION",
                                null,
                                roots,
                                descendants,
                                types,
                                "NONE"));
    }

    private RuntimeSchema.Table table(DataCenter.FieldOptions options) {
        return new RuntimeSchema.Table(
                "public",
                "biz_test",
                null,
                null,
                List.of(field),
                Map.of("org", options),
                Map.of(),
                null,
                true);
    }

    private void organization(Long id, String name, Long parent, int type, Integer status) {
        var organization = new OrganizationDO();
        organization.setId(id);
        organization.setOrgName(name);
        organization.setOrgCode(name);
        organization.setParentId(parent);
        organization.setOrgType(type);
        organization.setStatus(status);
        records.add(organization);
    }
}

package com.richuang.os.nocode.tools;

import static org.assertj.core.api.Assertions.*;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.richuang.os.nocode.api.ApplicationReports;
import com.richuang.os.nocode.enums.ReportBucketEnum;
import com.richuang.os.nocode.runtime.dal.query.*;
import com.richuang.os.nocode.runtime.dal.support.RuntimeConditionSql;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.session.defaults.DefaultSqlSessionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.InputStream;
import java.util.*;

/** 离线核对报表范围的值绑定和普通/组合视图参数路径，不执行主键列表查询。 */
class ReportRootScopeContractTest {
    private MybatisConfiguration configuration;
    private RuntimeConditionSql compiler;

    @BeforeEach
    void setup() throws Exception {
        configuration = MapperXmlCompatibilityTest.configuration();
        for (String name :
                List.of(
                        "RecordMapper",
                        "ReportMapper",
                        "DataViewMapper",
                        "RuntimeConditionFragments")) {
            String path = "/mapper/nocode/" + name + ".xml";
            try (InputStream input = getClass().getResourceAsStream(path)) {
                assertThat(input).isNotNull();
                new XMLMapperBuilder(input, configuration, path, configuration.getSqlFragments())
                        .parse();
            }
        }
        compiler = new RuntimeConditionSql();
        ReflectionTestUtils.setField(
                compiler, "sqlSessionFactory", new DefaultSqlSessionFactory(configuration));
        ReflectionTestUtils.invokeMethod(compiler, "initialize");
    }

    @Test
    void bindsRawNullAndPredicateValuesWithoutInliningOrMaterializingKeys() {
        String raw = "B?' OR true --";
        ReportStatement source = source(raw);
        ReportRootScope scope = compiler.reportRootScope(source, "id");
        assertThat(scope.sql())
                .contains(
                        "EXISTS", "dashboard_scope(record_id)", "__dashboard_outer__.\"id\"::text");
        assertThat(scope.sql()).doesNotContain(raw, "LIMIT", "OFFSET");
        assertThat(scope.values()).contains(raw, "10002", "Asia/Shanghai", null);
        RecordStatement business = base().dashboardScope(scope);
        for (String name : List.of("rows", "count")) {
            BoundSql mapped = bound("RecordMapper." + name, business);
            assertThat(mapped.getSql()).contains("dashboard_scope.record_id = t.\"id\"::text");
            assertThat(mapped.getParameterMappings())
                    .anySatisfy(
                            mapping ->
                                    assertThat(mapping.getProperty())
                                            .startsWith("dashboardScope.values["));
            assertThat(mapped.getSql()).doesNotContain(raw, "__dashboard_outer__");
        }
        assertThat(
                        business.page(10, 20)
                                .scope(null, null, null, null)
                                .conditions(null)
                                .dashboardScope())
                .isEqualTo(scope);
    }

    @Test
    void keepsInternalReportAliasAndNestedBindingsForComposedViewRoot() {
        ReportRootScope scope = compiler.reportRootScope(source("B"), "id");
        DataViewStatement view =
                new DataViewStatement(
                        base().dashboardScope(scope),
                        List.of(),
                        List.of(),
                        null,
                        null,
                        null,
                        false,
                        20,
                        0);
        for (String name : List.of("rows", "count")) {
            BoundSql mapped = bound("DataViewMapper." + name, view);
            assertThat(mapped.getSql()).contains("dashboard_scope.record_id = r.\"id\"::text");
            assertThat(mapped.getSql()).contains("t.\"occurred_at\"", "t.\"amount\"");
            assertThat(mapped.getParameterMappings())
                    .anySatisfy(
                            mapping ->
                                    assertThat(mapping.getProperty())
                                            .startsWith("root.dashboardScope.values["));
        }
    }

    private BoundSql bound(String name, Object statement) {
        return configuration
                .getMappedStatement("com.richuang.os.nocode.runtime.dal.mapper." + name)
                .getBoundSql(statement);
    }

    private RecordStatement base() {
        return new RecordStatement(
                "public",
                "biz_scope",
                "id",
                Map.of("100", "name"),
                List.of("name"),
                List.of("amount"),
                true,
                null,
                null,
                null,
                null,
                null,
                List.of(),
                "{}",
                null,
                false,
                20,
                0,
                List.of(),
                "{}",
                "10002",
                false);
    }

    private ReportStatement source(String raw) {
        QueryWrapper<Object> read = new QueryWrapper<>();
        read.setParamAlias("dynamicQuery");
        read.eq("t.creator", "10002").eq("t.\"name\"", raw);
        QueryWrapper<Object> metric = new QueryWrapper<>();
        metric.setParamAlias("predicates.m0");
        metric.gt("t.\"amount\"", 10);
        ReportStatement.Dimension date =
                new ReportStatement.Dimension("t", "occurred_at", ReportBucketEnum.MONTH, true);
        return new ReportStatement(
                base().conditions(read),
                List.of(date),
                List.of(),
                List.of(),
                List.of(new ApplicationReports.Metric("sum", "金额", "SUM", "101")),
                List.of("amount"),
                null,
                false,
                "Asia/Shanghai",
                null,
                null,
                Collections.singletonList(null),
                100,
                null,
                false,
                Map.of("m0", metric),
                0);
    }
}

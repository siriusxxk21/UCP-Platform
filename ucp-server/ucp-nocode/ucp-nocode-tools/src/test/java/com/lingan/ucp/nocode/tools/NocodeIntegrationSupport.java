package com.lingan.ucp.nocode.tools;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lingan.ucp.framework.datapermission.config.OsDataPermissionAutoConfiguration;
import com.lingan.ucp.framework.datapermission.core.rule.DataPermissionRuleFactoryImpl;
import com.lingan.ucp.framework.mybatis.config.OsMybatisAutoConfiguration;
import com.lingan.ucp.framework.mybatis.core.metadata.DatabaseMetadataReader;
import com.lingan.ucp.framework.mybatis.core.metadata.PostgreSqlDatabaseMetadataMapper;
import com.lingan.ucp.framework.mybatis.core.metadata.PostgreSqlDatabaseMetadataReader;
import com.lingan.ucp.module.infra.service.file.FileService;
import com.lingan.ucp.nocode.api.*;
import com.lingan.ucp.nocode.metadata.dal.mapper.ObjectDraftMapper;
import com.lingan.ucp.nocode.metadata.service.object.DataObjectApiImpl;
import com.lingan.ucp.nocode.metadata.service.object.DraftValidator;
import com.lingan.ucp.nocode.metadata.service.object.ObjectDesignService;
import com.lingan.ucp.nocode.metadata.service.object.ObjectDraftService;
import com.lingan.ucp.nocode.metadata.service.table.DataTableService;

import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;

import javax.sql.DataSource;

/** 开发环境集成测试的共享 Spring/MyBatis 装配与自有对象夹具；不包含业务验收用例。 */
class NocodeIntegrationSupport {
    static ConfigurableApplicationContext toolContext;
    static org.springframework.context.annotation.AnnotationConfigApplicationContext
            servicesContext;
    static NocodeDatabaseTool databaseTool;
    static DataSource ds;
    static JdbcTemplate jdbc;
    static ObjectMapper mapper;
    static ObjectDraftService service;
    static ObjectDesignService designs;
    static DataTableService tables;
    static com.lingan.ucp.nocode.schema.service.publish.SchemaPublishService publisher;
    static com.lingan.ucp.nocode.metadata.dal.mapper.DataCenterMapper centerMapper;
    static com.lingan.ucp.nocode.metadata.dal.mapper.ObjectDraftMapper draftMapper;
    static com.lingan.ucp.framework.mybatis.core.metadata.PostgreSqlCommandMapper commandMapper;
    static PlatformTransactionManager manager;
    static WriteFailureInterceptor writeFailure;
    static SqlSessionTemplate session;
    static DatabaseMetadataReader databaseMetadata;
    String prefix;

    @BeforeAll
    static void connect() throws Exception {
        toolContext = NocodeToolContext.open();
        databaseTool = toolContext.getBean(NocodeDatabaseTool.class);
        ds = toolContext.getBean(DataSource.class);
        jdbc = toolContext.getBean(JdbcTemplate.class);
        mapper = new ObjectMapper().findAndRegisterModules();
        manager = toolContext.getBean(PlatformTransactionManager.class);
        // JdbcTemplate 仅供测试夹具清理/独立断言；被测服务完全经过真实 MyBatis 会话。
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        com.baomidou.mybatisplus.core.config.GlobalConfig globalConfig =
                new com.baomidou.mybatisplus.core.config.GlobalConfig()
                        .setDbConfig(
                                new com.baomidou.mybatisplus.core.config.GlobalConfig.DbConfig())
                        .setMetaObjectHandler(
                                new OsMybatisAutoConfiguration().defaultMetaObjectHandler());
        com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils.setGlobalConfig(
                configuration, globalConfig);
        configuration.addMapper(ObjectDraftMapper.class);
        configuration.addMapper(com.lingan.ucp.module.system.dal.mysql.permission.MenuMapper.class);
        configuration.addMapper(
                com.lingan.ucp.nocode.metadata.dal.mapper.OrderedCalculationStateMapper.class);
        configuration.addMapper(
                com.lingan.ucp.nocode.application.dal.mapper.TaskEntryAccessMapper.class);
        configuration.addMapper(
                com.lingan.ucp.nocode.metadata.dal.mapper.SelectionMigrationMapper.class);
        configuration.addMapper(
                com.lingan.ucp.nocode.metadata.dal.mapper.FieldConversionMapper.class);
        configuration.addMapper(
                com.lingan.ucp.nocode.metadata.dal.mapper.FieldConstraintCheckMapper.class);
        configuration.addMapper(
                com.lingan.ucp.nocode.metadata.dal.mapper.ObjectOperationDataMapper.class);
        configuration.addMapper(com.lingan.ucp.nocode.metadata.dal.mapper.DataCenterMapper.class);
        configuration.addMapper(
                com.lingan.ucp.framework.mybatis.core.metadata.PostgreSqlCommandMapper.class);
        configuration.addMapper(PostgreSqlDatabaseMetadataMapper.class);
        configuration.addMapper(com.lingan.ucp.nocode.runtime.dal.mapper.RecordMapper.class);
        configuration.addMapper(com.lingan.ucp.nocode.runtime.dal.mapper.TaskCenterMapper.class);
        configuration.addMapper(com.lingan.ucp.nocode.runtime.dal.mapper.TaskWorkEntryMapper.class);
        configuration.addMapper(
                com.lingan.ucp.nocode.runtime.dal.mapper.ObjectMaintenanceMapper.class);
        configuration.addMapper(com.lingan.ucp.nocode.runtime.dal.mapper.DataViewMapper.class);
        configuration.addMapper(
                com.lingan.ucp.nocode.runtime.dal.mapper.HandlingRequestMapper.class);
        configuration.addMapper(
                com.lingan.ucp.nocode.runtime.dal.mapper.DocumentReceiptMapper.class);
        configuration.addMapper(
                com.lingan.ucp.nocode.runtime.dal.mapper.DetailPositionMapper.class);
        configuration.addMapper(com.lingan.ucp.nocode.runtime.dal.mapper.RecordHistoryMapper.class);
        configuration.addMapper(com.lingan.ucp.nocode.runtime.dal.mapper.ReportMapper.class);
        configuration.addMapper(com.lingan.ucp.nocode.work.dal.mapper.draft.WorkDraftMapper.class);
        configuration.addMapper(
                com.lingan.ucp.nocode.work.dal.mapper.submission.WorkSubmissionMapper.class);
        configuration.addMapper(
                com.lingan.ucp.nocode.application.dal.mapper.RecordProcessMapper.class);
        configuration.addMapper(
                com.lingan.ucp.nocode.runtime.dal.mapper.BusinessCounterMapper.class);
        configuration.addMapper(
                com.lingan.ucp.nocode.application.dal.mapper.ApplicationAccessMapper.class);
        configuration.addMapper(
                com.lingan.ucp.nocode.application.dal.mapper.ObjectApplicationGrantMapper.class);
        configuration.addMapper(
                com.lingan.ucp.nocode.application.dal.mapper.ApplicationObjectFollowMapper.class);
        configuration.addMapper(
                com.lingan.ucp.nocode.application.dal.mapper.DateTriggerMapper.class);
        configuration.addMapper(
                com.lingan.ucp.nocode.runtime.dal.mapper.BizDirectoryBindingMapper.class);
        configuration.addMapper(
                com.lingan.ucp.nocode.runtime.dal.mapper.BizAttachmentBindingMapper.class);
        configuration.addMapper(
                com.lingan.ucp.nocode.runtime.dal.mapper.BizUploadSessionMapper.class);
        configuration.addMapper(
                com.lingan.ucp.nocode.runtime.dal.mapper.BizFileRetentionMapper.class);
        configuration.addMapper(com.lingan.ucp.nocode.runtime.dal.mapper.BizFileTaskMapper.class);
        configuration.addMapper(com.lingan.ucp.nocode.runtime.dal.mapper.BizFileBrowseMapper.class);
        configuration.addMapper(com.lingan.ucp.nocode.runtime.dal.mapper.BizFileMarkMapper.class);
        MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(ds);
        factory.setConfiguration(configuration);
        factory.setGlobalConfig(globalConfig);
        PathMatchingResourcePatternResolver resources = new PathMatchingResourcePatternResolver();
        ArrayList<org.springframework.core.io.Resource> mapperResources =
                new ArrayList<org.springframework.core.io.Resource>();
        mapperResources.addAll(List.of(resources.getResources("classpath*:mapper/nocode/*.xml")));
        mapperResources.addAll(List.of(resources.getResources("classpath*:mapper/database/*.xml")));
        factory.setMapperLocations(
                mapperResources.toArray(org.springframework.core.io.Resource[]::new));
        writeFailure = new WriteFailureInterceptor();
        com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor interceptors =
                new OsMybatisAutoConfiguration().mybatisPlusInterceptor();
        // 与正式启动一样保留数据权限拦截器；全局对象元数据目前没有部门行过滤规则。
        new OsDataPermissionAutoConfiguration()
                .dataPermissionRuleHandler(
                        interceptors, new DataPermissionRuleFactoryImpl(List.of()));
        factory.setPlugins(interceptors, writeFailure);
        session = new SqlSessionTemplate(factory.getObject());
        com.lingan.ucp.nocode.application.dal.mapper.ApplicationMapper applicationMapper =
                session.getMapper(
                        com.lingan.ucp.nocode.application.dal.mapper.ApplicationMapper.class);
        draftMapper = session.getMapper(ObjectDraftMapper.class);
        databaseMetadata =
                new PostgreSqlDatabaseMetadataReader(
                        session.getMapper(PostgreSqlDatabaseMetadataMapper.class));
        centerMapper =
                session.getMapper(com.lingan.ucp.nocode.metadata.dal.mapper.DataCenterMapper.class);
        commandMapper =
                session.getMapper(
                        com.lingan.ucp.framework.mybatis.core.metadata.PostgreSqlCommandMapper
                                .class);
        // 被测业务组件由真实 Spring 容器完成 @Resource 注入及 @PostConstruct 初始化。
        // 复用当前工具上下文的数据源与事务管理器，不通过测试专用构造器跳过装配。
        servicesContext =
                new org.springframework.context.annotation.AnnotationConfigApplicationContext();
        servicesContext.setParent(toolContext);
        servicesContext.register(TransactionConfiguration.class);
        servicesContext.registerBean(
                com.lingan.ucp.module.system.dal.mysql.permission.MenuMapper.class,
                () ->
                        session.getMapper(
                                com.lingan.ucp.module.system.dal.mysql.permission.MenuMapper
                                        .class));
        servicesContext.registerBean(
                com.lingan.ucp.module.system.service.permission.PermissionService.class,
                () ->
                        org.mockito.Mockito.mock(
                                com.lingan.ucp.module.system.service.permission.PermissionService
                                        .class));
        servicesContext.registerBean(
                com.lingan.ucp.module.system.service.tenant.TenantService.class,
                () ->
                        org.mockito.Mockito.mock(
                                com.lingan.ucp.module.system.service.tenant.TenantService.class));
        servicesContext.register(
                com.lingan.ucp.module.system.api.permission.MenuApiImpl.class,
                com.lingan.ucp.module.system.service.permission.MenuServiceImpl.class,
                com.lingan.ucp.nocode.application.service.resource.ApplicationNavigation.class);
        servicesContext.registerBean(
                com.lingan.ucp.nocode.runtime.dal.mapper.TaskCenterMapper.class,
                () ->
                        session.getMapper(
                                com.lingan.ucp.nocode.runtime.dal.mapper.TaskCenterMapper.class));
        servicesContext.registerBean(
                com.lingan.ucp.nocode.runtime.dal.mapper.TaskWorkEntryMapper.class,
                () ->
                        session.getMapper(
                                com.lingan.ucp.nocode.runtime.dal.mapper.TaskWorkEntryMapper
                                        .class));
        servicesContext.registerBean(
                com.lingan.ucp.module.msg.api.IMsgSendService.class,
                () ->
                        org.mockito.Mockito.mock(
                                com.lingan.ucp.module.msg.api.IMsgSendService.class));
        servicesContext.register(
                com.lingan.ucp.nocode.runtime.service.taskcenter.TaskWorkEntryServiceImpl.class,
                com.lingan.ucp.nocode.runtime.service.taskcenter.TaskBusinessFileServiceImpl.class,
                com.lingan.ucp.nocode.runtime.service.taskcenter.TaskHandlingHistoryServiceImpl
                        .class,
                com.lingan.ucp.nocode.runtime.service.taskcenter.TaskBusiness.class,
                com.lingan.ucp.nocode.runtime.service.taskcenter.TaskBoundViews.class,
                com.lingan.ucp.nocode.runtime.service.taskcenter.TaskStandardWork.class,
                com.lingan.ucp.nocode.runtime.service.taskcenter.TaskDataPolicies.class,
                com.lingan.ucp.nocode.runtime.service.taskcenter.TaskDataPolicyCompiler.class,
                com.lingan.ucp.nocode.runtime.service.task.TaskGroupRuntime.class,
                com.lingan.ucp.nocode.runtime.service.taskcenter.TaskFormRuntimeServiceImpl.class,
                com.lingan.ucp.nocode.runtime.service.taskcenter.TaskPageQueries.class,
                com.lingan.ucp.nocode.runtime.service.taskcenter.TaskAssignments.class,
                com.lingan.ucp.nocode.runtime.service.taskcenter.TaskWorkflowProtection.class,
                com.lingan.ucp.nocode.runtime.service.taskcenter.TaskClaimGroups.class,
                com.lingan.ucp.nocode.runtime.service.taskcenter.TaskScheduling.class,
                com.lingan.ucp.nocode.runtime.service.taskcenter.TaskChecklists.class,
                com.lingan.ucp.nocode.runtime.service.taskcenter.TaskCenterServiceImpl.class);
        // 完整底座中日志组件占用 recordService；保留该 Bean 才能发现 @Resource 的名称冲突。
        servicesContext.registerBean(
                "recordService", com.mzt.logapi.service.impl.DefaultLogRecordServiceImpl.class);
        servicesContext.registerBean(ObjectMapper.class, () -> mapper);
        servicesContext.registerBean(
                com.lingan.ucp.nocode.runtime.dal.mapper.ObjectMaintenanceMapper.class,
                () ->
                        session.getMapper(
                                com.lingan.ucp.nocode.runtime.dal.mapper.ObjectMaintenanceMapper
                                        .class));
        servicesContext.registerBean(
                org.apache.ibatis.session.SqlSessionFactory.class,
                () -> session.getSqlSessionFactory());
        servicesContext.registerBean(
                com.lingan.ucp.nocode.runtime.dal.mapper.HandlingRequestMapper.class,
                () ->
                        session.getMapper(
                                com.lingan.ucp.nocode.runtime.dal.mapper.HandlingRequestMapper
                                        .class));
        servicesContext.registerBean(
                "bpmProcessTaskApi",
                com.lingan.ucp.module.bpm.api.task.BpmProcessTaskApi.class,
                () ->
                        org.mockito.Mockito.mock(
                                com.lingan.ucp.module.bpm.api.task.BpmProcessTaskApi.class));
        servicesContext.registerBean(
                com.lingan.ucp.nocode.runtime.dal.mapper.DataViewMapper.class,
                () ->
                        session.getMapper(
                                com.lingan.ucp.nocode.runtime.dal.mapper.DataViewMapper.class));
        servicesContext.registerBean(
                com.lingan.ucp.nocode.runtime.dal.mapper.DetailPositionMapper.class,
                () ->
                        session.getMapper(
                                com.lingan.ucp.nocode.runtime.dal.mapper.DetailPositionMapper
                                        .class));
        servicesContext.registerBean(
                com.lingan.ucp.nocode.runtime.dal.mapper.DocumentReceiptMapper.class,
                () ->
                        session.getMapper(
                                com.lingan.ucp.nocode.runtime.dal.mapper.DocumentReceiptMapper
                                        .class));
        servicesContext.registerBean(
                com.lingan.ucp.nocode.runtime.dal.mapper.RecordHistoryMapper.class,
                () ->
                        session.getMapper(
                                com.lingan.ucp.nocode.runtime.dal.mapper.RecordHistoryMapper
                                        .class));
        servicesContext.registerBean(
                com.lingan.ucp.nocode.runtime.dal.mapper.BizDirectoryBindingMapper.class,
                () ->
                        session.getMapper(
                                com.lingan.ucp.nocode.runtime.dal.mapper.BizDirectoryBindingMapper
                                        .class));
        servicesContext.registerBean(
                com.lingan.ucp.nocode.runtime.dal.mapper.BizAttachmentBindingMapper.class,
                () ->
                        session.getMapper(
                                com.lingan.ucp.nocode.runtime.dal.mapper.BizAttachmentBindingMapper
                                        .class));
        servicesContext.registerBean(
                com.lingan.ucp.nocode.runtime.dal.mapper.BizUploadSessionMapper.class,
                () ->
                        session.getMapper(
                                com.lingan.ucp.nocode.runtime.dal.mapper.BizUploadSessionMapper
                                        .class));
        servicesContext.registerBean(
                com.lingan.ucp.nocode.runtime.dal.mapper.BizFileRetentionMapper.class,
                () ->
                        session.getMapper(
                                com.lingan.ucp.nocode.runtime.dal.mapper.BizFileRetentionMapper
                                        .class));
        servicesContext.registerBean(
                com.lingan.ucp.nocode.runtime.dal.mapper.BizFileTaskMapper.class,
                () ->
                        session.getMapper(
                                com.lingan.ucp.nocode.runtime.dal.mapper.BizFileTaskMapper.class));
        servicesContext.registerBean(
                com.lingan.ucp.nocode.runtime.dal.mapper.BizFileBrowseMapper.class,
                () ->
                        session.getMapper(
                                com.lingan.ucp.nocode.runtime.dal.mapper.BizFileBrowseMapper
                                        .class));
        servicesContext.registerBean(
                com.lingan.ucp.nocode.runtime.dal.mapper.BizFileMarkMapper.class,
                () ->
                        session.getMapper(
                                com.lingan.ucp.nocode.runtime.dal.mapper.BizFileMarkMapper.class));
        // 业务文件网盘边界替身：集成验证聚焦无代码侧绑定逻辑，网盘实现由独立链路覆盖。
        servicesContext.registerBean(
                "driveBizFileApi",
                com.lingan.ucp.module.drive.api.bizfile.DriveBizFileApi.class,
                () ->
                        org.mockito.Mockito.mock(
                                com.lingan.ucp.module.drive.api.bizfile.DriveBizFileApi.class));
        servicesContext.registerBean(
                com.lingan.ucp.module.system.service.organization.OrganizationService.class,
                () ->
                        org.mockito.Mockito.mock(
                                com.lingan.ucp.module.system.service.organization
                                        .OrganizationService.class));
        servicesContext.registerBean(
                com.lingan.ucp.module.system.api.organization.OrganizationApi.class,
                () ->
                        org.mockito.Mockito.mock(
                                com.lingan.ucp.module.system.api.organization.OrganizationApi
                                        .class));
        servicesContext.registerBean(
                com.lingan.ucp.module.system.service.user.AdminUserService.class,
                () ->
                        org.mockito.Mockito.mock(
                                com.lingan.ucp.module.system.service.user.AdminUserService.class));
        servicesContext.registerBean(
                com.lingan.ucp.module.system.api.dict.DictDataApi.class,
                () ->
                        org.mockito.Mockito.mock(
                                com.lingan.ucp.module.system.api.dict.DictDataApi.class));
        servicesContext.registerBean(
                com.lingan.ucp.module.system.service.dept.DeptService.class,
                () ->
                        org.mockito.Mockito.mock(
                                com.lingan.ucp.module.system.service.dept.DeptService.class));
        servicesContext.registerBean(
                com.lingan.ucp.module.system.service.dept.PostService.class,
                () ->
                        org.mockito.Mockito.mock(
                                com.lingan.ucp.module.system.service.dept.PostService.class));
        // 底座身份 API 为边界替身；授权配置和业务读写仍使用当前数据库真实 Mapper。
        servicesContext.registerBean(
                com.lingan.ucp.module.system.api.dept.DeptApi.class,
                () ->
                        org.mockito.Mockito.mock(
                                com.lingan.ucp.module.system.api.dept.DeptApi.class));
        servicesContext.registerBean(
                com.lingan.ucp.module.system.api.dept.PostApi.class,
                () ->
                        org.mockito.Mockito.mock(
                                com.lingan.ucp.module.system.api.dept.PostApi.class));
        servicesContext.registerBean(
                com.lingan.ucp.module.bpm.api.definition.BpmUserGroupApi.class,
                () ->
                        org.mockito.Mockito.mock(
                                com.lingan.ucp.module.bpm.api.definition.BpmUserGroupApi.class));
        servicesContext.registerBean(
                FileService.class, () -> org.mockito.Mockito.mock(FileService.class));
        servicesContext.registerBean(
                com.lingan.ucp.module.system.api.user.AdminUserApi.class,
                () ->
                        org.mockito.Mockito.mock(
                                com.lingan.ucp.module.system.api.user.AdminUserApi.class));
        servicesContext.registerBean(
                com.lingan.ucp.module.system.api.permission.RoleApi.class,
                () ->
                        org.mockito.Mockito.mock(
                                com.lingan.ucp.module.system.api.permission.RoleApi.class));
        servicesContext.registerBean(
                com.lingan.ucp.module.system.api.permission.PermissionApi.class,
                () ->
                        org.mockito.Mockito.mock(
                                com.lingan.ucp.module.system.api.permission.PermissionApi.class));
        servicesContext.registerBean(
                com.lingan.ucp.nocode.application.dal.mapper.ApplicationAccessMapper.class,
                () ->
                        session.getMapper(
                                com.lingan.ucp.nocode.application.dal.mapper.ApplicationAccessMapper
                                        .class));
        servicesContext.registerBean(
                com.lingan.ucp.nocode.runtime.dal.mapper.RecordMapper.class,
                () ->
                        session.getMapper(
                                com.lingan.ucp.nocode.runtime.dal.mapper.RecordMapper.class));
        servicesContext.registerBean(
                com.lingan.ucp.nocode.runtime.dal.mapper.ReportMapper.class,
                () ->
                        session.getMapper(
                                com.lingan.ucp.nocode.runtime.dal.mapper.ReportMapper.class));
        servicesContext.registerBean(
                com.lingan.ucp.module.bpm.api.definition.BpmProcessDefinitionApi.class,
                () ->
                        org.mockito.Mockito.mock(
                                com.lingan.ucp.module.bpm.api.definition.BpmProcessDefinitionApi
                                        .class));
        servicesContext.registerBean(
                com.lingan.ucp.module.bpm.api.task.BpmProcessInstanceApi.class,
                () ->
                        org.mockito.Mockito.mock(
                                com.lingan.ucp.module.bpm.api.task.BpmProcessInstanceApi.class));
        servicesContext.registerBean(
                com.lingan.ucp.nocode.application.dal.mapper.RecordProcessMapper.class,
                () ->
                        session.getMapper(
                                com.lingan.ucp.nocode.application.dal.mapper.RecordProcessMapper
                                        .class));
        servicesContext.registerBean(DatabaseMetadataReader.class, () -> databaseMetadata);
        servicesContext.registerBean(
                com.lingan.ucp.nocode.work.dal.mapper.draft.WorkDraftMapper.class,
                () ->
                        session.getMapper(
                                com.lingan.ucp.nocode.work.dal.mapper.draft.WorkDraftMapper.class));
        servicesContext.registerBean(
                com.lingan.ucp.nocode.work.dal.mapper.submission.WorkSubmissionMapper.class,
                () ->
                        session.getMapper(
                                com.lingan.ucp.nocode.work.dal.mapper.submission
                                        .WorkSubmissionMapper.class));
        servicesContext.registerBean(
                com.lingan.ucp.nocode.runtime.dal.mapper.BusinessCounterMapper.class,
                () ->
                        session.getMapper(
                                com.lingan.ucp.nocode.runtime.dal.mapper.BusinessCounterMapper
                                        .class));
        servicesContext.registerBean(ObjectDraftMapper.class, () -> draftMapper);
        servicesContext.registerBean(
                com.lingan.ucp.nocode.metadata.dal.mapper.OrderedCalculationStateMapper.class,
                () ->
                        session.getMapper(
                                com.lingan.ucp.nocode.metadata.dal.mapper
                                        .OrderedCalculationStateMapper.class));
        servicesContext.registerBean(
                com.lingan.ucp.nocode.application.dal.mapper.TaskEntryAccessMapper.class,
                () ->
                        session.getMapper(
                                com.lingan.ucp.nocode.application.dal.mapper.TaskEntryAccessMapper
                                        .class));
        servicesContext.registerBean(
                com.lingan.ucp.nocode.metadata.dal.mapper.SelectionMigrationMapper.class,
                () ->
                        session.getMapper(
                                com.lingan.ucp.nocode.metadata.dal.mapper.SelectionMigrationMapper
                                        .class));
        servicesContext.registerBean(
                com.lingan.ucp.nocode.metadata.dal.mapper.FieldConversionMapper.class,
                () ->
                        session.getMapper(
                                com.lingan.ucp.nocode.metadata.dal.mapper.FieldConversionMapper
                                        .class));
        servicesContext.registerBean(
                com.lingan.ucp.nocode.metadata.dal.mapper.FieldConstraintCheckMapper.class,
                () ->
                        session.getMapper(
                                com.lingan.ucp.nocode.metadata.dal.mapper.FieldConstraintCheckMapper
                                        .class));
        servicesContext.registerBean(
                com.lingan.ucp.nocode.metadata.dal.mapper.ObjectOperationDataMapper.class,
                () ->
                        session.getMapper(
                                com.lingan.ucp.nocode.metadata.dal.mapper.ObjectOperationDataMapper
                                        .class));
        servicesContext.registerBean(
                com.lingan.ucp.nocode.application.dal.mapper.ObjectApplicationGrantMapper.class,
                () ->
                        session.getMapper(
                                com.lingan.ucp.nocode.application.dal.mapper
                                        .ObjectApplicationGrantMapper.class));
        servicesContext.registerBean(
                com.lingan.ucp.nocode.application.dal.mapper.ApplicationMapper.class,
                () -> applicationMapper);
        servicesContext.registerBean(
                com.lingan.ucp.nocode.application.dal.mapper.LinkageTriggerMapper.class,
                () ->
                        session.getMapper(
                                com.lingan.ucp.nocode.application.dal.mapper.LinkageTriggerMapper
                                        .class));
        servicesContext.registerBean(
                com.lingan.ucp.nocode.application.dal.mapper.ApplicationObjectFollowMapper.class,
                () ->
                        session.getMapper(
                                com.lingan.ucp.nocode.application.dal.mapper
                                        .ApplicationObjectFollowMapper.class));
        servicesContext.registerBean(
                com.lingan.ucp.nocode.application.dal.mapper.DateTriggerMapper.class,
                () ->
                        session.getMapper(
                                com.lingan.ucp.nocode.application.dal.mapper.DateTriggerMapper
                                        .class));
        servicesContext.registerBean(
                com.lingan.ucp.nocode.metadata.dal.mapper.DataCenterMapper.class,
                () -> centerMapper);
        servicesContext.registerBean(
                com.lingan.ucp.framework.mybatis.core.metadata.PostgreSqlCommandMapper.class,
                () -> commandMapper);
        servicesContext.register(
                com.lingan.ucp.nocode.application.service.resource.ApplicationAutomationValidator
                        .class,
                com.lingan.ucp.nocode.application.service.resource.ApplicationAutomationCatalog
                        .class,
                com.lingan.ucp.nocode.application.service.resource.ApplicationRuleWriteExclusion
                        .class,
                com.lingan.ucp.nocode.runtime.service.record.RecordAutomations.class,
                // 按日期自动执行：账本、运行与定时入口（测试容器不开调度，用例直接调 tick / runNow）。
                com.lingan.ucp.nocode.application.service.resource.ApplicationDateTriggers.class,
                com.lingan.ucp.nocode.runtime.service.record.RecordDateTriggers.class,
                com.lingan.ucp.nocode.runtime.job.application.DateTriggerScheduler.class,
                com.lingan.ucp.nocode.application.service.resource.ApplicationLinkageTriggers.class,
                com.lingan.ucp.nocode.runtime.service.record.RecordLinkageSync.class,
                com.lingan.ucp.nocode.runtime.service.maintenance.LinkageSyncServiceImpl.class,
                com.lingan.ucp.nocode.runtime.service.record.RecordCaptures.class,
                com.lingan.ucp.nocode.metadata.service.object.ObjectDesignCodec.class,
                com.lingan.ucp.nocode.metadata.service.formula.OrderedCalculationStateService.class,
                com.lingan.ucp.nocode.runtime.service.record.OrderedRecordCalculations.class,
                com.lingan.ucp.nocode.runtime.service.maintenance.OrderedCalibrationServiceImpl
                        .class,
                com.lingan.ucp.nocode.metadata.service.object.ObjectDesignReader.class,
                com.lingan.ucp.nocode.metadata.service.object.ObjectDesignFields.class,
                com.lingan.ucp.nocode.metadata.service.object.ObjectInactiveFields.class,
                com.lingan.ucp.nocode.metadata.service.object.ObjectDesignPartsWriter.class,
                com.lingan.ucp.nocode.metadata.service.object.ObjectMemberDeployment.class,
                com.lingan.ucp.nocode.metadata.service.object.ObjectDesignReferences.class,
                com.lingan.ucp.nocode.metadata.service.object.ObjectLifecycleChecks.class,
                com.lingan.ucp.nocode.metadata.service.object.ObjectOperationDataQueries.class,
                com.lingan.ucp.nocode.metadata.service.object.ObjectFieldOperationChecks.class,
                com.lingan.ucp.nocode.metadata.service.object.ObjectOperationPreviewServiceImpl
                        .class,
                com.lingan.ucp.nocode.runtime.service.task.TaskEntryRuntimeInvocation.class,
                com.lingan.ucp.nocode.runtime.service.task.TaskEntryDraftAccess.class,
                com.lingan.ucp.nocode.runtime.service.task.TaskEntryRuntimeQueries.class,
                com.lingan.ucp.nocode.schema.service.compile.SchemaTableDefinitions.class,
                com.lingan.ucp.nocode.schema.service.compile.SchemaColumnChanges.class,
                com.lingan.ucp.nocode.schema.service.compile.FieldConversionPlanner.class,
                com.lingan.ucp.nocode.schema.service.convert.FieldSwitchPreviewServiceImpl.class,
                com.lingan.ucp.nocode.schema.service.compile.SchemaFieldConstraintChecks.class,
                com.lingan.ucp.nocode.report.service.dataset.ReportFieldConversionDependencies
                        .class,
                com.lingan.ucp.nocode.metadata.service.object.ObjectFieldConversionDependencies
                        .class,
                com.lingan.ucp.nocode.application.service.resource
                        .ApplicationFieldConversionDependencies.class,
                com.lingan.ucp.nocode.schema.service.compile.SchemaIndexChanges.class,
                com.lingan.ucp.nocode.schema.service.compile.SchemaRelationChanges.class,
                com.lingan.ucp.nocode.schema.service.publish.SchemaPublishContext.class,
                com.lingan.ucp.nocode.application.service.resource.ApplicationResourceContext.class,
                com.lingan.ucp.nocode.application.service.resource.ApplicationPageValidator.class,
                com.lingan.ucp.nocode.application.service.resource.ApplicationViewValidator.class,
                com.lingan.ucp.nocode.runtime.service.live.RecordChangeCollector.class,
                // 与正式装配一样：每次保存提交后都会交给它。这个容器里没有网盘接口的实现，它静默不工作。
                com.lingan.ucp.nocode.runtime.service.folder.RecordFolderAutoCreator.class,
                com.lingan.ucp.nocode.runtime.service.record.RecordTransactions.class,
                com.lingan.ucp.nocode.runtime.service.record.RecordPersistence.class,
                com.lingan.ucp.nocode.runtime.service.record.RecordDocumentValidation.class,
                com.lingan.ucp.nocode.runtime.service.record.RecordModelProjection.class,
                com.lingan.ucp.nocode.runtime.service.record.RecordContextResolver.class,
                com.lingan.ucp.nocode.runtime.service.record.RecordSelectionSupport.class,
                com.lingan.ucp.nocode.runtime.service.record.RecordRelationAccess.class,
                com.lingan.ucp.nocode.runtime.service.record.RecordReadService.class,
                com.lingan.ucp.nocode.runtime.service.record.RecordQueryService.class,
                com.lingan.ucp.nocode.runtime.service.rules.FieldRuleServiceImpl.class,
                com.lingan.ucp.nocode.runtime.service.record.RecordDetailWriter.class,
                com.lingan.ucp.nocode.runtime.service.record.RecordWriteService.class,
                com.lingan.ucp.nocode.runtime.service.rules.FieldRuleEnforcer.class,
                com.lingan.ucp.nocode.runtime.service.record.RecordDeletionService.class,
                com.lingan.ucp.nocode.runtime.service.bizfile.BizFileDirectoryNamer.class,
                com.lingan.ucp.nocode.runtime.service.bizfile.BizUploadSessionService.class,
                com.lingan.ucp.nocode.runtime.service.bizfile.BizFileRetentionService.class,
                com.lingan.ucp.nocode.runtime.service.bizfile.BizFileUploadService.class,
                com.lingan.ucp.nocode.runtime.service.bizfile.BizFileBindingService.class,
                com.lingan.ucp.nocode.runtime.service.bizfile.BizFilePublishInspection.class,
                com.lingan.ucp.nocode.runtime.service.bizfile.BizFileBrowseService.class,
                com.lingan.ucp.nocode.runtime.service.bizfile.BizFileMarkService.class,
                com.lingan.ucp.nocode.runtime.service.bizfile.BizUploadCleanupService.class,
                com.lingan.ucp.nocode.runtime.service.record.RecordMaintenanceChecks.class,
                com.lingan.ucp.nocode.runtime.service.maintenance.ObjectColumnMaintenance.class,
                com.lingan.ucp.nocode.runtime.service.maintenance.ObjectDataMaintenanceServiceImpl
                        .class,
                com.lingan.ucp.nocode.runtime.dal.support.RuntimeConditionSql.class,
                com.lingan.ucp.nocode.runtime.service.record.RecordConditions.class,
                com.lingan.ucp.nocode.runtime.service.record.RelatedFormService.class,
                com.lingan.ucp.nocode.runtime.service.handling.BusinessHandlingServiceImpl.class,
                com.lingan.ucp.nocode.application.service.resource.DataViewDefinitions.class,
                com.lingan.ucp.nocode.runtime.service.view.DataViewService.class,
                com.lingan.ucp.nocode.application.service.resource.TaskEntryConfigValidator.class,
                com.lingan.ucp.nocode.application.service.task.TaskEntryPolicyServiceImpl.class,
                com.lingan.ucp.nocode.runtime.service.task.TaskEntryRuntimeScope.class,
                com.lingan.ucp.nocode.runtime.service.task.TaskEntryRuntimeServiceImpl.class,
                com.lingan.ucp.nocode.runtime.service.history.RecordHistoryTracker.class,
                com.lingan.ucp.nocode.runtime.service.record.DocumentReceipts.class,
                com.lingan.ucp.nocode.runtime.service.record.DetailPositions.class,
                com.lingan.ucp.nocode.runtime.service.history.RecordHistoryServiceImpl.class,
                com.lingan.ucp.nocode.runtime.service.history.HistoryDetailProjection.class,
                DraftValidator.class,
                com.lingan.ucp.nocode.application.service.resource.ApplicationProcessDefinition
                        .class,
                com.lingan.ucp.nocode.runtime.service.record.RecordProcessService.class,
                com.lingan.ucp.nocode.application.service.application.ApplicationService.class,
                com.lingan.ucp.nocode.application.service.application.ApplicationUpgradeServiceImpl
                        .class,
                com.lingan.ucp.nocode.application.service.application.ApplicationFollowServiceImpl
                        .class,
                com.lingan.ucp.nocode.application.service.published.ApplicationVersionContext.class,
                com.lingan.ucp.nocode.application.service.published.ApplicationPublishedServiceImpl
                        .class,
                com.lingan.ucp.nocode.work.service.draft.WorkDraftServiceImpl.class,
                com.lingan.ucp.nocode.work.service.submission.WorkSubmissionServiceImpl.class,
                com.lingan.ucp.nocode.runtime.service.work.WorkFormServiceImpl.class,
                com.lingan.ucp.nocode.runtime.service.work.WorkDraftQueryServiceImpl.class,
                com.lingan.ucp.nocode.application.service.authorization
                        .ApplicationAuthorizationService.class,
                com.lingan.ucp.nocode.application.service.sharing.ObjectSharingService.class,
                com.lingan.ucp.nocode.application.service.sharing.ObjectGrantValidator.class,
                com.lingan.ucp.nocode.application.service.sharing.ImpliedObjects.class,
                com.lingan.ucp.nocode.application.service.sharing.SystemReadAccess.class,
                com.lingan.ucp.nocode.application.service.resource.ApplicationResourceValidator
                        .class,
                com.lingan.ucp.nocode.application.service.resource.ApplicationReportValidator.class,
                com.lingan.ucp.nocode.runtime.service.report.ApplicationReportService.class,
                com.lingan.ucp.nocode.runtime.service.report.ReportAggregateReader.class,
                com.lingan.ucp.nocode.runtime.service.report.ReportGrantConditions.class,
                com.lingan.ucp.nocode.application.service.resource.ApplicationPageBindings.class,
                com.lingan.ucp.nocode.application.service.application.ApplicationObjectContract
                        .class,
                com.lingan.ucp.nocode.runtime.service.record.RuntimeSchema.class,
                com.lingan.ucp.nocode.runtime.service.record.RecordValues.class,
                com.lingan.ucp.nocode.runtime.service.record.RecordAutoNumbers.class,
                com.lingan.ucp.nocode.runtime.service.record.RecordRelations.class,
                com.lingan.ucp.nocode.runtime.service.record.RecordSummaries.class,
                com.lingan.ucp.nocode.runtime.service.access.ScopeConditions.class,
                com.lingan.ucp.nocode.runtime.service.record.FixedViewConditions.class,
                com.lingan.ucp.nocode.runtime.service.record.RecordCalculations.class,
                com.lingan.ucp.nocode.runtime.service.selection.RecordDirectoryValues.class,
                com.lingan.ucp.nocode.runtime.service.selection.SelectionCatalog.class,
                com.lingan.ucp.nocode.runtime.service.access.ApplicationRuntimePolicy.class,
                com.lingan.ucp.nocode.runtime.service.application.ApplicationRuntimeService.class,
                com.lingan.ucp.nocode.runtime.service.application.ApplicationBusinessRules.class,
                com.lingan.ucp.nocode.runtime.service.record.RecordService.class,
                com.lingan.ucp.nocode.runtime.service.record.RecordQueryAccess.class,
                com.lingan.ucp.nocode.runtime.service.record.ReferenceRecords.class,
                ObjectDraftService.class,
                ObjectDesignService.class,
                com.lingan.ucp.nocode.metadata.service.table.TableBindingService.class,
                DataTableService.class,
                DataObjectApiImpl.class,
                com.lingan.ucp.nocode.schema.service.compile.SchemaCompiler.class,
                com.lingan.ucp.nocode.schema.service.selection.SelectionMigrationService.class,
                com.lingan.ucp.nocode.schema.service.publish.SchemaPublishService.class,
                com.lingan.ucp.nocode.schema.service.reconcile.ObjectReconcileService.class,
                com.lingan.ucp.nocode.web.RecordExcelService.class,
                com.lingan.ucp.nocode.web.ObjectImportService.class);
        servicesContext.refresh();
        org.mockito.Mockito.when(
                        servicesContext
                                .getBean(
                                        com.lingan.ucp.framework.common.biz.system.permission
                                                .PermissionCommonApi.class)
                                .hasAnyPermissions(
                                        10001L,
                                        com.lingan.ucp.nocode.application.service.sharing
                                                .ObjectSharingService.MANAGE_PERMISSION))
                .thenReturn(true);
        org.mockito.Mockito.when(
                        servicesContext
                                .getBean(
                                        com.lingan.ucp.framework.common.biz.system.permission
                                                .PermissionCommonApi.class)
                                .hasAnyPermissions(10001L, "nocode:app:manage"))
                .thenReturn(true);
        service = servicesContext.getBean(ObjectDraftService.class);
        designs = servicesContext.getBean(ObjectDesignService.class);
        tables = servicesContext.getBean(DataTableService.class);
        publisher =
                servicesContext.getBean(
                        com.lingan.ucp.nocode.schema.service.publish.SchemaPublishService.class);
        databaseTool.flyway().validate();
    }

    @AfterAll
    static void close() {
        if (servicesContext != null) servicesContext.close();
        if (toolContext != null) {
            toolContext.close();
        }
    }

    /** 默认历史截止来自数据库；对应的人工事件夹具也使用同一时钟，避免开发虚拟机时钟漂移。 */
    static java.time.Instant databaseNow() {
        return jdbc.queryForObject("SELECT clock_timestamp()", java.time.OffsetDateTime.class)
                .toInstant();
    }

    @BeforeEach
    void name() {
        prefix = "test_b1_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    /** 引用即授权已写入默认上限（六类操作）；此夹具在其上放宽到全部操作（含发起流程）供旧业务用例使用。 */
    /**
     * 测试夹具：改写应用当前发布版本的快照，并同步快照校验和。只用于伪造「旧版本代码发布、现行发布校验已不再放行」的存量配置；
     * 对象发布会核对已发布应用快照的完整性，改了快照而不同步校验和会被拒绝。
     */
    static void rewritePublishedSnapshot(
            String app, java.util.function.Consumer<com.fasterxml.jackson.databind.JsonNode> edit) {
        try {
            long id = Long.parseLong(app);
            Integer version =
                    jdbc.queryForObject(
                            "SELECT published_version FROM public.nocode_application WHERE id=?",
                            Integer.class,
                            id);
            var tree =
                    mapper.readTree(
                            jdbc.queryForObject(
                                    "SELECT definition_json::text FROM"
                                            + " public.nocode_application_version WHERE"
                                            + " application_id=? AND version_no=?",
                                    String.class,
                                    id,
                                    version));
            edit.accept(tree);
            String canonical =
                    mapper.copy()
                            .enable(
                                    com.fasterxml.jackson.databind.SerializationFeature
                                            .ORDER_MAP_ENTRIES_BY_KEYS)
                            .writeValueAsString(
                                    mapper.treeToValue(tree, ApplicationCenter.Snapshot.class));
            jdbc.update(
                    "UPDATE public.nocode_application_version SET definition_json=CAST(? AS"
                            + " jsonb), checksum=? WHERE application_id=? AND version_no=?",
                    canonical,
                    cn.hutool.crypto.digest.DigestUtil.sha256Hex(canonical),
                    id,
                    version);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * 测试夹具：应用对对象的共享上限，按对象最新发布版展开成普通 ID 集合。库里存的可能是「全部」哨兵（首次引用时的默认授权就是），
     * 用例要在它的基础上「去掉某个字段」「只留某几项」时必须先展开。没有授权或已撤销时返回 null。
     */
    static ApplicationAuthorization.ObjectGrant resolvedPermission(String object, String app) {
        ApplicationAuthorization.ObjectGrant stored =
                servicesContext
                        .getBean(
                                com.lingan.ucp.nocode.application.service.sharing
                                        .ObjectSharingService.class)
                        .storedPermission(object, app);
        if (stored == null) return null;
        return servicesContext
                .getBean(
                        com.lingan.ucp.nocode.application.service.sharing.ObjectGrantValidator
                                .class)
                .resolve(stored, servicesContext.getBean(DataObjectApi.class).getPublished(object));
    }

    /**
     * 测试夹具：单独关掉一个应用对它引用的全部对象的自动跟随（等同于设计者在应用里把每个对象的开关拨成「关」）。
     * 自动跟随默认开着：对象一发布，应用就跟上了。专门验证「应用按固定的对象版本运行、由人同步后才换版本」的用例先调用它。
     */
    static void stopFollowing(String app) {
        jdbc.update(
                "INSERT INTO public.nocode_application_object_follow(application_id, object_id,"
                    + " enabled, creator, updater) SELECT a.id, (r->>'objectId')::bigint, false,"
                    + " 'fixture', 'fixture' FROM public.nocode_application a CROSS JOIN LATERAL"
                    + " jsonb_array_elements(a.design_json->'objects') r WHERE a.id=? ON CONFLICT"
                    + " (application_id, object_id) DO UPDATE SET enabled=false",
                Long.valueOf(app));
    }

    static void grantApplicationObjects(String app) {
        com.lingan.ucp.nocode.application.service.sharing.ObjectSharingService sharing =
                servicesContext.getBean(
                        com.lingan.ucp.nocode.application.service.sharing.ObjectSharingService
                                .class);
        com.lingan.ucp.nocode.application.service.application.ApplicationService applications =
                servicesContext.getBean(
                        com.lingan.ucp.nocode.application.service.application.ApplicationService
                                .class);
        DataObjectApi objects = servicesContext.getBean(DataObjectApi.class);
        for (ApplicationCenter.ObjectReference reference :
                applications.get(app).draft().objects()) {
            DataCenter.Definition d =
                    objects.getVersion(reference.objectId(), reference.versionNo()).definition();
            Set<String> fields =
                    d.fields().stream()
                            .map(f -> f.id())
                            .collect(java.util.stream.Collectors.toSet());
            Set<String> details =
                    d.details().stream()
                            .filter(t -> "ACTIVE".equals(t.state()))
                            .map(t -> t.id())
                            .collect(java.util.stream.Collectors.toSet());
            Set<String> relations =
                    d.relations().stream()
                            .filter(r -> "MANY_TO_MANY".equals(r.kind()))
                            .map(r -> r.id())
                            .collect(java.util.stream.Collectors.toSet());
            Set<String> actions =
                    Arrays.stream(com.lingan.ucp.nocode.enums.ApplicationActionEnum.values())
                            .map(a -> a.getCode())
                            .collect(java.util.stream.Collectors.toSet());
            Optional<ObjectSharing.Grant> previous =
                    sharing.forApplication(app).stream()
                            .filter(g -> g.objectId().equals(d.objectId()))
                            .findFirst();
            sharing.save(
                    new ObjectSharing.Save(
                            d.objectId(),
                            app,
                            previous.map(ObjectSharing.Grant::revision).orElse(0),
                            new ApplicationAuthorization.ObjectGrant(
                                    d.objectId(),
                                    actions,
                                    "ALL",
                                    fields,
                                    fields,
                                    details,
                                    details,
                                    relations,
                                    relations),
                            "测试夹具显式授权"),
                    10001);
        }
    }

    @AfterEach
    void clean() {
        new TransactionTemplate(manager)
                .executeWithoutResult(
                        status -> {
                            List<Long> ids =
                                    jdbc.queryForList(
                                            "SELECT id FROM public.nocode_object WHERE object_code"
                                                    + " LIKE ?",
                                            Long.class,
                                            prefix + "%");
                            // 仅清理本测试前缀拥有的表，先解除关系，再删除元数据。
                            for (Long id : ids) {
                                // 维护入口也会留存修订、历史和幂等收据；只清理本次随机前缀对象。
                                jdbc.update(
                                        "DELETE FROM public.nocode_document_receipt WHERE"
                                                + " object_id=?",
                                        id);
                                jdbc.update(
                                        "DELETE FROM public.nocode_record_history WHERE"
                                                + " object_id=?",
                                        id);
                                jdbc.update(
                                        "DELETE FROM public.nocode_record_history_head WHERE"
                                                + " object_id=?",
                                        id);
                                jdbc.update(
                                        "DELETE FROM public.nocode_detail_position WHERE"
                                                + " object_id=?",
                                        id);
                                // 数据联动自动更新的索引行：留着会让后续用例里的无关对象也去取全局执行锁。
                                jdbc.update(
                                        "DELETE FROM public.nocode_linkage_trigger WHERE"
                                                + " source_object_id=? OR target_object_id=?",
                                        id,
                                        id);
                                var physical =
                                        new ArrayList<>(
                                                jdbc.queryForList(
                                                        "SELECT DISTINCT t.table_name FROM"
                                                            + " public.nocode_object_table t JOIN"
                                                            + " public.nocode_object_version v ON"
                                                            + " v.id=t.object_version_id JOIN"
                                                            + " public.nocode_object o ON"
                                                            + " o.id=v.object_id WHERE o.id=? AND"
                                                            + " o.source_type='GENERATED'",
                                                        String.class,
                                                        id));
                                for (Long relationId :
                                        jdbc.queryForList(
                                                "SELECT DISTINCT r.stable_relation_id FROM"
                                                        + " public.nocode_relation r JOIN"
                                                        + " public.nocode_object_version v ON"
                                                        + " v.id=r.object_version_id WHERE"
                                                        + " v.object_id=? AND"
                                                        + " r.relation_kind='MANY_TO_MANY'",
                                                Long.class,
                                                id))
                                    physical.add(
                                            DataTableService.relationTable(
                                                    databaseMetadata,
                                                    "public",
                                                    id.toString(),
                                                    relationId.toString()));
                                for (String table : physical) {
                                    if (!table.startsWith("biz_" + prefix)
                                            && !table.startsWith("nocode_data_" + prefix)
                                            && !table.startsWith("nocode_data_r_" + id + "_")
                                            && !table.startsWith("biz_r_" + id + "_"))
                                        throw new IllegalStateException(
                                                "Fixture table outside test ownership");
                                    jdbc.execute(
                                            "DROP TABLE IF EXISTS public.\""
                                                    + table
                                                    + "\" CASCADE");
                                }
                                jdbc.update(
                                        "DELETE FROM public.nocode_resource_dependency WHERE"
                                                + " target_object_id=?",
                                        id);
                                jdbc.update(
                                        "DELETE FROM public.nocode_ordered_calculation_state WHERE"
                                                + " object_id=?",
                                        id);
                                jdbc.update(
                                        "DELETE FROM public.nocode_publish_plan WHERE object_id=?",
                                        id);
                                jdbc.update(
                                        "DELETE FROM public.nocode_deployment WHERE object_id=?",
                                        id);
                                jdbc.update(
                                        "DELETE FROM public.nocode_relation WHERE object_version_id"
                                                + " IN (SELECT id FROM public.nocode_object_version"
                                                + " WHERE object_id=?)",
                                        id);
                                jdbc.update(
                                        "DELETE FROM public.nocode_index_definition WHERE"
                                            + " object_version_id IN (SELECT id FROM"
                                            + " public.nocode_object_version WHERE object_id=?)",
                                        id);
                            }
                            jdbc.update(
                                    "DELETE FROM public.nocode_operation_log WHERE"
                                            + " resource_type='DATA_TABLE' AND resource_id LIKE ?",
                                    "public.biz_" + prefix + "%");
                            for (Long id : ids) {
                                jdbc.update(
                                        "UPDATE public.nocode_object SET"
                                            + " reconciliation_hash=NULL,reconciliation_version_id=NULL"
                                            + " WHERE id=?",
                                        id);
                                jdbc.update(
                                        "DELETE FROM public.nocode_operation_log WHERE"
                                                + " resource_type='DATA_OBJECT' AND object_id=?",
                                        id);
                                jdbc.update(
                                        "DELETE FROM public.nocode_field WHERE object_version_id IN"
                                            + " (SELECT id FROM public.nocode_object_version WHERE"
                                            + " object_id=?)",
                                        id);
                                jdbc.update(
                                        "DELETE FROM public.nocode_object_table WHERE"
                                            + " object_version_id IN (SELECT id FROM"
                                            + " public.nocode_object_version WHERE object_id=?)",
                                        id);
                                jdbc.update(
                                        "DELETE FROM public.nocode_object_version WHERE"
                                                + " object_id=?",
                                        id);
                                jdbc.update("DELETE FROM public.nocode_object WHERE id=?", id);
                            }
                        });
    }

    FieldDefinition field(String key, String code, String type, int sort) {
        return new FieldDefinition(
                key, null, code, "字段" + sort, type, null, null, null, false, false, sort);
    }

    SaveObjectDraft createRequest(String suffix) {
        return new SaveObjectDraft(
                null,
                null,
                prefix + suffix,
                "验证对象",
                null,
                "biz_" + prefix + suffix,
                "new-title",
                List.of(field("new-title", "name", "TEXT", 0)),
                List.of());
    }

    /** 专项 Context 与生产一致启用服务事务，避免业务记录和贡献分别自动提交。 */
    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    @org.springframework.transaction.annotation.EnableTransactionManagement(proxyTargetClass = true)
    static class TransactionConfiguration {}

    ObjectDraft create(String suffix) {
        return service.create(createRequest(suffix), 10001L, UUID.randomUUID());
    }

    SaveObjectDraft edit(
            ObjectDraft object, List<FieldDefinition> fields, List<String> removals, String title) {
        return new SaveObjectDraft(
                object.id(),
                object.lockVersion(),
                object.objectCode(),
                "修改后的对象",
                object.description(),
                object.tableName(),
                title,
                fields,
                removals);
    }
}

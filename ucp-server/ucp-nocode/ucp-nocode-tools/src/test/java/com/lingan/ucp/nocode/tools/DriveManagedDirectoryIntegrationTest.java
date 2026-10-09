package com.lingan.ucp.nocode.tools;

import static org.assertj.core.api.Assertions.*;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.lingan.ucp.framework.datapermission.config.OsDataPermissionAutoConfiguration;
import com.lingan.ucp.framework.datapermission.core.rule.DataPermissionRuleFactoryImpl;
import com.lingan.ucp.framework.mybatis.config.OsMybatisAutoConfiguration;
import com.lingan.ucp.module.drive.dal.dataobject.entry.DriveEntryDO;
import com.lingan.ucp.module.drive.dal.mysql.entry.DriveEntryMapper;
import com.lingan.ucp.module.drive.dal.mysql.space.DriveSpaceMapper;
import com.lingan.ucp.module.drive.enums.entry.DriveEntryTypeEnum;
import com.lingan.ucp.module.drive.enums.entry.DriveTrashStateEnum;
import com.lingan.ucp.module.drive.service.bizfile.DriveBizFileApiImpl;

import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;
import java.util.function.LongConsumer;

import javax.sql.DataSource;

/** 真实开发库验证目录写入与正式数据权限拦截器兼容；所有夹具均在同一事务回滚。 */
class DriveManagedDirectoryIntegrationTest {
    private static ConfigurableApplicationContext context;
    private static JdbcTemplate jdbc;
    private static TransactionTemplate transaction;
    private static DriveEntryMapper entries;
    private static DriveBizFileApiImpl drive;

    @BeforeAll
    static void open() throws Exception {
        context = NocodeToolContext.open();
        jdbc = context.getBean(JdbcTemplate.class);
        transaction = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(DriveEntryMapper.class);
        configuration.addMapper(DriveSpaceMapper.class);
        MybatisPlusInterceptor interceptors =
                new OsMybatisAutoConfiguration().mybatisPlusInterceptor();
        new OsDataPermissionAutoConfiguration()
                .dataPermissionRuleHandler(
                        interceptors, new DataPermissionRuleFactoryImpl(List.of()));
        MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(context.getBean(DataSource.class));
        factory.setConfiguration(configuration);
        factory.setMapperLocations(
                new PathMatchingResourcePatternResolver()
                        .getResources("classpath*:mapper/drive/*.xml"));
        factory.setPlugins(interceptors);
        SqlSessionTemplate session = new SqlSessionTemplate(factory.getObject());
        entries = session.getMapper(DriveEntryMapper.class);
        drive = new DriveBizFileApiImpl();
        ReflectionTestUtils.setField(drive, "entryMapper", entries);
        ReflectionTestUtils.setField(
                drive, "spaceMapper", session.getMapper(DriveSpaceMapper.class));
    }

    @AfterAll
    static void close() {
        if (context != null) context.close();
    }

    @Test
    void createsRootAndChildDirectoriesAndReturnsRealIds() {
        withSpace(
                spaceId -> {
                    Long root = drive.createManagedDirectory(spaceId, 0L, "记录目录", 10001L);
                    Long child = drive.createManagedDirectory(spaceId, root, "公司附件", 10001L);
                    DriveEntryDO saved = entries.selectById(child);
                    assertThat(saved.getParentId()).isEqualTo(root);
                    assertThat(saved.getSpaceId()).isEqualTo(spaceId);
                    assertThat(saved.getManagedBiz()).isTrue();
                    assertThat(saved.getCreator()).isEqualTo("10001");
                    assertThat(saved.getUpdater()).isEqualTo("10001");
                });
    }

    @Test
    void conflictDoesNotAbortTransactionOrReuseAnotherIdentityDirectory() {
        withSpace(
                spaceId -> {
                    Long first = drive.createManagedDirectory(spaceId, 0L, "记录目录", 10001L);
                    // 强制走数据库唯一冲突分支，验证 ON CONFLICT 不破坏外层保存事务。
                    DriveEntryDO conflict = folder(spaceId, "记录目录");
                    // MyBatis-Plus 可能预先分配 ID；是否成功必须按受影响行数判定。
                    assertThat(entries.insertManagedFolderIfAbsent(conflict, 10001L)).isZero();
                    assertThat(entries.selectBySpaceAndParentAndName(spaceId, 0L, "记录目录").getId())
                            .isEqualTo(first);
                    Long second = drive.createManagedDirectory(spaceId, 0L, "记录目录", 10001L);
                    assertThat(second).isNotEqualTo(first);
                    assertThat(entries.selectById(second).getName()).isEqualTo("记录目录 (2)");
                });
    }

    private static DriveEntryDO folder(long spaceId, String name) {
        return DriveEntryDO.builder()
                .spaceId(spaceId)
                .parentId(0L)
                .name(name)
                .type(DriveEntryTypeEnum.FOLDER.getCode())
                .size(0L)
                .inheritParent(true)
                .trashState(DriveTrashStateEnum.NORMAL.getCode())
                .managedBiz(true)
                .lockVersion(0)
                .build();
    }

    private void withSpace(LongConsumer assertion) {
        String name = "__drive_directory_regression_" + UUID.randomUUID();
        transaction.executeWithoutResult(
                status -> {
                    status.setRollbackOnly();
                    Long spaceId =
                            jdbc.queryForObject(
                                    "INSERT INTO drive_space(name,type) VALUES (?,'BIZ') RETURNING"
                                            + " id",
                                    Long.class,
                                    name);
                    assertion.accept(spaceId);
                });
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM drive_space WHERE name=?", Long.class, name))
                .isZero();
    }
}

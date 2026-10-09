package com.richuang.os.nocode.tools;

import static com.richuang.os.module.drive.enums.ErrorCodeConstants.*;

import static org.assertj.core.api.Assertions.*;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.richuang.os.framework.common.biz.system.permission.PermissionCommonApi;
import com.richuang.os.framework.common.exception.ErrorCode;
import com.richuang.os.framework.common.exception.ServiceException;
import com.richuang.os.framework.datapermission.config.OsDataPermissionAutoConfiguration;
import com.richuang.os.framework.datapermission.core.rule.DataPermissionRuleFactoryImpl;
import com.richuang.os.framework.mybatis.config.OsMybatisAutoConfiguration;
import com.richuang.os.module.drive.controller.admin.entry.vo.*;
import com.richuang.os.module.drive.controller.admin.permission.vo.DrivePermissionSaveReqVO;
import com.richuang.os.module.drive.dal.dataobject.entry.DriveEntryDO;
import com.richuang.os.module.drive.dal.mysql.entry.DriveEntryMapper;
import com.richuang.os.module.drive.dal.mysql.mark.DriveUserMarkMapper;
import com.richuang.os.module.drive.dal.mysql.permission.DrivePermissionMapper;
import com.richuang.os.module.drive.dal.mysql.share.DriveShareMapper;
import com.richuang.os.module.drive.dal.mysql.share.DriveShareSubjectMapper;
import com.richuang.os.module.drive.dal.mysql.space.DriveSpaceMapper;
import com.richuang.os.module.drive.enums.permission.DrivePermissionRoleEnum;
import com.richuang.os.module.drive.service.bizfile.DriveBizFileApiImpl;
import com.richuang.os.module.drive.service.entry.DriveEntryService;
import com.richuang.os.module.drive.service.entry.DriveEntryServiceImpl;
import com.richuang.os.module.drive.service.permission.DrivePermissionService;
import com.richuang.os.module.drive.service.permission.DrivePermissionServiceImpl;
import com.richuang.os.module.drive.service.permission.DriveSubjectResolver;
import com.richuang.os.module.drive.service.space.DriveSpaceServiceImpl;
import com.richuang.os.module.drive.service.storage.DriveStorageService;
import com.richuang.os.module.infra.api.file.FileApi;
import com.richuang.os.module.infra.dal.dataobject.file.FileDO;
import com.richuang.os.module.infra.service.file.FileService;
import com.richuang.os.module.system.api.dept.DeptApi;
import com.richuang.os.module.system.api.user.AdminUserApi;
import com.richuang.os.module.system.api.user.dto.AdminUserRespDTO;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.*;
import org.mockito.Mockito;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import javax.sql.DataSource;

/**
 * 普通网盘入口（不带限定子树）的既有行为：个人空间、团队空间里的角色计算、列表、搜索、新建、上传、改名、移动、复制、删除、恢复、彻底删除、
 * 回收站、继承边界；业务空间对没有空间治理权限的人仍然整个不可见。真实网盘实现 + 测试库。
 *
 * <p>本类只用记录文件夹改动之前就有的类与方法：同一份用例在改动前的基线代码上同样全部通过，用来证明这些行为没有变。
 *
 * <p>人物：甲是团队空间归属人；乙在目录「部门」上被授予可编辑；丙在空间上被授予可查看；丁没有任何授权。都不持有任何权限码、没有部门。
 */
class DrivePlainBehaviorIntegrationTest {
    private static final long A = 940001L;
    private static final long B = 940002L;
    private static final long C = 940003L;
    private static final long D = 940004L;
    private static final String PREFIX =
            "__plain_drive_" + UUID.randomUUID().toString().replace("-", "");

    private static ConfigurableApplicationContext tool;
    private static AnnotationConfigApplicationContext context;
    private static JdbcTemplate jdbc;
    private static DriveEntryMapper entries;
    private static DriveEntryService entryService;
    private static DrivePermissionService permissionService;
    private static DriveBizFileApiImpl bizFiles;
    private static final Map<Long, byte[]> CONTENTS = new ConcurrentHashMap<>();
    private static final AtomicLong FILE_IDS = new AtomicLong(8_900_000_000L);

    private long team;
    private long dept;
    private long report;
    private long note;

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement(proxyTargetClass = true)
    @Import({
        DriveSubjectResolver.class,
        DrivePermissionServiceImpl.class,
        DriveSpaceServiceImpl.class,
        DriveEntryServiceImpl.class,
        DriveBizFileApiImpl.class
    })
    static class Beans {}

    @BeforeAll
    static void open() throws Exception {
        tool = NocodeToolContext.open();
        jdbc = tool.getBean(JdbcTemplate.class);
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        GlobalConfig globalConfig =
                new GlobalConfig()
                        .setDbConfig(new GlobalConfig.DbConfig().setIdType(IdType.ASSIGN_ID))
                        .setMetaObjectHandler(
                                new OsMybatisAutoConfiguration().defaultMetaObjectHandler());
        com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils.setGlobalConfig(
                configuration, globalConfig);
        List<Class<?>> mappers =
                List.of(
                        DriveEntryMapper.class,
                        DriveSpaceMapper.class,
                        DrivePermissionMapper.class,
                        DriveShareMapper.class,
                        DriveShareSubjectMapper.class,
                        DriveUserMarkMapper.class);
        mappers.forEach(configuration::addMapper);
        MybatisPlusInterceptor interceptors =
                new OsMybatisAutoConfiguration().mybatisPlusInterceptor();
        new OsDataPermissionAutoConfiguration()
                .dataPermissionRuleHandler(
                        interceptors, new DataPermissionRuleFactoryImpl(List.of()));
        MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(tool.getBean(DataSource.class));
        factory.setConfiguration(configuration);
        factory.setGlobalConfig(globalConfig);
        factory.setMapperLocations(
                new PathMatchingResourcePatternResolver()
                        .getResources("classpath*:mapper/drive/*.xml"));
        factory.setPlugins(interceptors);
        SqlSessionTemplate session = new SqlSessionTemplate(factory.getObject());

        context = new AnnotationConfigApplicationContext();
        context.setParent(tool);
        for (Class<?> mapper : mappers) register(session, mapper);
        // 谁都不持有任何权限码；用户资料只给出「存在且启用」，没有部门
        context.registerBean(
                PermissionCommonApi.class, () -> Mockito.mock(PermissionCommonApi.class));
        context.registerBean(AdminUserApi.class, DrivePlainBehaviorIntegrationTest::users);
        context.registerBean(DeptApi.class, () -> Mockito.mock(DeptApi.class));
        context.registerBean(FileService.class, () -> Mockito.mock(FileService.class));
        context.registerBean(FileApi.class, DrivePlainBehaviorIntegrationTest::files);
        context.registerBean(DriveStorageService.class, DrivePlainBehaviorIntegrationTest::storage);
        context.register(Beans.class);
        context.refresh();
        entries = context.getBean(DriveEntryMapper.class);
        entryService = context.getBean(DriveEntryService.class);
        permissionService = context.getBean(DrivePermissionService.class);
        bizFiles = context.getBean(DriveBizFileApiImpl.class);
    }

    @SuppressWarnings("unchecked")
    private static <T> void register(SqlSessionTemplate session, Class<?> type) {
        Class<T> mapper = (Class<T>) type;
        context.registerBean(mapper, () -> session.getMapper(mapper));
    }

    private static AdminUserApi users() {
        AdminUserApi api = Mockito.mock(AdminUserApi.class);
        Mockito.when(api.getUser(Mockito.anyLong()))
                .thenAnswer(
                        call -> {
                            AdminUserRespDTO user = new AdminUserRespDTO();
                            user.setId(call.getArgument(0));
                            user.setNickname("用户" + call.getArgument(0));
                            user.setStatus(0);
                            return user;
                        });
        return api;
    }

    private static DriveStorageService storage() {
        DriveStorageService storage = Mockito.mock(DriveStorageService.class);
        Mockito.when(storage.getSelectedConfigId()).thenReturn(null);
        return storage;
    }

    private static FileApi files() {
        FileApi api = Mockito.mock(FileApi.class);
        Mockito.when(
                        api.createProtectedFile(
                                Mockito.any(InputStream.class),
                                Mockito.anyLong(),
                                Mockito.any(),
                                Mockito.any(),
                                Mockito.any()))
                .thenAnswer(
                        call ->
                                stored(
                                        call.<InputStream>getArgument(0).readAllBytes(),
                                        call.getArgument(2),
                                        call.getArgument(4)));
        Mockito.when(
                        api.createProtectedFile(
                                Mockito.any(byte[].class),
                                Mockito.any(),
                                Mockito.any(),
                                Mockito.any()))
                .thenAnswer(
                        call ->
                                stored(
                                        call.getArgument(0),
                                        call.getArgument(1),
                                        call.getArgument(3)));
        Mockito.when(api.getFileContent(Mockito.anyLong()))
                .thenAnswer(call -> CONTENTS.get(call.<Long>getArgument(0)));
        Mockito.when(api.getFileContentLength(Mockito.anyLong()))
                .thenAnswer(
                        call -> {
                            byte[] bytes = CONTENTS.get(call.<Long>getArgument(0));
                            return bytes == null ? null : (long) bytes.length;
                        });
        Mockito.when(api.getFileContentStream(Mockito.anyLong(), Mockito.anyLong()))
                .thenAnswer(
                        call -> {
                            byte[] bytes = CONTENTS.get(call.<Long>getArgument(0));
                            int offset = (int) Math.min(call.<Long>getArgument(1), bytes.length);
                            return new ByteArrayInputStream(bytes, offset, bytes.length - offset);
                        });
        return api;
    }

    private static FileDO stored(byte[] bytes, String name, String type) {
        long id = FILE_IDS.incrementAndGet();
        CONTENTS.put(id, bytes);
        FileDO file = new FileDO();
        file.setId(id);
        file.setName(name);
        file.setType(type);
        file.setSize((long) bytes.length);
        return file;
    }

    @AfterAll
    static void close() {
        try {
            String like = PREFIX + "%";
            String owned = "(SELECT id FROM public.drive_space WHERE name LIKE ?)";
            jdbc.update("DELETE FROM public.drive_permission WHERE space_id IN " + owned, like);
            jdbc.update("DELETE FROM public.drive_entry WHERE space_id IN " + owned, like);
            jdbc.update("DELETE FROM public.drive_space WHERE name LIKE ?", like);
        } finally {
            if (context != null) context.close();
            if (tool != null) tool.close();
        }
    }

    @BeforeEach
    void fixture(TestInfo info) {
        team = space("T-" + info.getTestMethod().orElseThrow().getName(), "TEAM", A);
        dept = folder(team, 0L, "部门", A);
        report = upload(team, dept, "report.txt", A).getId();
        note = upload(team, 0L, "note.txt", A).getId();
        grant(team, dept, B, "EDITOR");
        grant(team, 0L, C, "VIEWER");
    }

    private static long space(String name, String type, Long owner) {
        return jdbc.queryForObject(
                "INSERT INTO public.drive_space(name,type,owner_id) VALUES (?,?,?) RETURNING id",
                Long.class,
                PREFIX + name,
                type,
                owner);
    }

    private static void grant(long space, long entry, long user, String role) {
        jdbc.update(
                "INSERT INTO"
                        + " public.drive_permission(space_id,entry_id,subject_type,subject_id,role)"
                        + " VALUES (?,?,'USER',?,?)",
                space,
                entry,
                user,
                role);
    }

    private static long folder(long space, long parent, String name, long actor) {
        DriveEntryFolderCreateReqVO request = new DriveEntryFolderCreateReqVO();
        request.setSpaceId(space);
        request.setParentId(parent);
        request.setName(name);
        return entryService.createFolder(request, actor);
    }

    private static DriveEntryDO upload(long space, long parent, String name, long actor) {
        byte[] bytes = name.getBytes(StandardCharsets.UTF_8);
        return entryService.uploadFile(
                space,
                parent,
                name,
                "text/plain",
                bytes.length,
                new ByteArrayInputStream(bytes),
                actor);
    }

    private static List<String> list(long space, long parent, long actor) {
        DriveEntryListReqVO request = new DriveEntryListReqVO();
        request.setSpaceId(space);
        request.setParentId(parent);
        return entryService.getEntryList(request, actor).stream()
                .map(DriveEntryDO::getName)
                .toList();
    }

    private static List<String> search(long space, String name, long actor) {
        DriveEntrySearchReqVO request = new DriveEntrySearchReqVO();
        request.setSpaceId(space);
        request.setName(name);
        return entryService.searchEntryList(request, actor).stream()
                .map(DriveEntryDO::getName)
                .toList();
    }

    private static void rename(long id, String name, long actor) {
        DriveEntryRenameReqVO request = new DriveEntryRenameReqVO();
        request.setId(id);
        request.setName(name);
        entryService.renameEntry(request, actor);
    }

    private static void move(long id, long target, long actor) {
        DriveEntryMoveReqVO request = new DriveEntryMoveReqVO();
        request.setId(id);
        request.setTargetParentId(target);
        entryService.moveEntry(request, actor);
    }

    private static Long copy(long id, long space, long target, long actor) {
        DriveEntryCopyReqVO request = new DriveEntryCopyReqVO();
        request.setId(id);
        request.setTargetSpaceId(space);
        request.setTargetParentId(target);
        return entryService.copyEntry(request, actor);
    }

    private static List<String> trash(long space, long actor) {
        DriveTrashListReqVO request = new DriveTrashListReqVO();
        request.setSpaceId(space);
        return entryService.getTrashList(request, actor).stream()
                .map(DriveEntryDO::getName)
                .toList();
    }

    private static DrivePermissionRoleEnum role(long space, long entry, long user) {
        return permissionService.getEffectiveRole(space, entry, user);
    }

    private static void rejected(ErrorCode code, ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(
                        ServiceException.class,
                        error -> assertThat(error.getCode()).isEqualTo(code.getCode()));
    }

    private static long usedBytes(long space) {
        return jdbc.queryForObject(
                "SELECT used_bytes FROM public.drive_space WHERE id=?", Long.class, space);
    }

    @Test
    void rolesFollowOwnershipGrantsAndInheritance() {
        assertThat(role(team, 0L, A)).isEqualTo(DrivePermissionRoleEnum.MANAGER);
        assertThat(role(team, report, A)).isEqualTo(DrivePermissionRoleEnum.MANAGER);
        assertThat(role(team, 0L, B)).isNull();
        assertThat(role(team, note, B)).isNull();
        assertThat(role(team, dept, B)).isEqualTo(DrivePermissionRoleEnum.EDITOR);
        assertThat(role(team, report, B)).isEqualTo(DrivePermissionRoleEnum.EDITOR);
        assertThat(role(team, 0L, C)).isEqualTo(DrivePermissionRoleEnum.VIEWER);
        assertThat(role(team, report, C)).isEqualTo(DrivePermissionRoleEnum.VIEWER);
        assertThat(role(team, 0L, D)).isNull();
        assertThat(role(team, report, D)).isNull();
        assertThat(role(team, -1L, A)).isEqualTo(DrivePermissionRoleEnum.MANAGER);
        assertThat(role(team, -1L, C)).isNull();
        assertThat(permissionService.getEffectiveRole(null, 0L, A)).isNull();
        assertThat(permissionService.getEffectiveRole(team, null, A)).isNull();
        assertThat(permissionService.getEffectiveRole(team, 0L, null)).isNull();
        assertThat(permissionService.getAccessibleSpaceIds(B)).contains(team);
        assertThat(permissionService.getAccessibleSpaceIds(D)).doesNotContain(team);

        // 关闭继承：空间级授权不再对这个目录生效，目录上自己的授权照常
        DriveEntryInheritReqVO inherit = new DriveEntryInheritReqVO();
        inherit.setId(dept);
        inherit.setInheritParent(false);
        entryService.updateInheritParent(inherit, A);
        assertThat(role(team, report, C)).isNull();
        assertThat(role(team, report, B)).isEqualTo(DrivePermissionRoleEnum.EDITOR);
        assertThat(role(team, note, C)).isEqualTo(DrivePermissionRoleEnum.VIEWER);
        rejected(PERMISSION_DENIED, () -> entryService.updateInheritParent(inherit, B));

        // 停用的空间对谁都没有角色
        jdbc.update("UPDATE public.drive_space SET status=1 WHERE id=?", team);
        assertThat(role(team, 0L, A)).isNull();
        assertThat(role(team, dept, B)).isNull();
    }

    @Test
    void personalSpaceIsOwnerOnly() {
        long personal = space("P-" + UUID.randomUUID(), "PERSONAL", A);
        long mine = folder(personal, 0L, "我的", A);
        grant(personal, mine, B, "MANAGER");

        assertThat(role(personal, 0L, A)).isEqualTo(DrivePermissionRoleEnum.MANAGER);
        assertThat(role(personal, mine, A)).isEqualTo(DrivePermissionRoleEnum.MANAGER);
        assertThat(role(personal, mine, B)).isNull();
        assertThat(list(personal, 0L, A)).containsExactly("我的");
        rejected(PERMISSION_DENIED, () -> list(personal, 0L, B));
        rejected(PERMISSION_DENIED, () -> list(personal, mine, B));
    }

    @Test
    void listingAndSearchRespectRoles() {
        assertThat(list(team, 0L, A)).containsExactly("部门", "note.txt");
        assertThat(list(team, 0L, C)).containsExactly("部门", "note.txt");
        assertThat(list(team, dept, B)).containsExactly("report.txt");
        rejected(PERMISSION_DENIED, () -> list(team, 0L, B));
        rejected(PERMISSION_DENIED, () -> list(team, 0L, D));
        rejected(ENTRY_NOT_EXISTS, () -> list(team, -1L, A));
        rejected(ENTRY_PARENT_NOT_FOLDER, () -> list(team, note, A));

        assertThat(search(team, "txt", A)).containsExactly("note.txt", "report.txt");
        assertThat(search(team, "txt", C)).containsExactly("note.txt", "report.txt");
        assertThat(search(team, "txt", B)).as("只返回有权查看的结果").containsExactly("report.txt");
        rejected(PERMISSION_DENIED, () -> search(team, "txt", D));

        assertThat(entryService.getEntryDetail(report, B).getRole()).isEqualTo("EDITOR");
        assertThat(entryService.getBreadcrumbList(report, C))
                .extracting(DriveEntryBreadcrumbRespVO::getName)
                .containsExactly("全部文件", "部门", "report.txt");
        rejected(PERMISSION_DENIED, () -> entryService.getEntry(note, B));
        rejected(PERMISSION_DENIED, () -> entryService.getContentInfo(report, D));
        assertThat(entryService.getContentInfo(report, C).getLength()).isEqualTo(10L);
    }

    @Test
    void writesRespectRolesAndKeepNamesUnique() {
        long before = usedBytes(team);
        long sub = folder(team, dept, "子", B);
        DriveEntryDO second = upload(team, dept, "report.txt", B);
        assertThat(second.getName()).as("同名文件自动加序号").isEqualTo("report (2).txt");
        assertThat(usedBytes(team)).isEqualTo(before + 10);
        rejected(ENTRY_NAME_DUPLICATE, () -> folder(team, dept, "子", B));
        rejected(ENTRY_NAME_INVALID, () -> folder(team, dept, "a/b", B));
        rejected(PERMISSION_DENIED, () -> folder(team, 0L, "根上", B));
        rejected(PERMISSION_DENIED, () -> folder(team, dept, "只读的人", C));
        rejected(PERMISSION_DENIED, () -> upload(team, dept, "x.txt", C));
        rejected(PERMISSION_DENIED, () -> upload(team, dept, "x.txt", D));
        rejected(ENTRY_PARENT_NOT_FOLDER, () -> upload(team, report, "x.txt", A));

        rename(report, "汇报.txt", B);
        assertThat(entries.selectById(report).getName()).isEqualTo("汇报.txt");
        rejected(PERMISSION_DENIED, () -> rename(report, "x.txt", C));
        rejected(PERMISSION_DENIED, () -> rename(note, "x.txt", B));

        move(report, sub, B);
        assertThat(entries.selectById(report).getParentId()).isEqualTo(sub);
        rejected(PERMISSION_DENIED, () -> move(report, 0L, B));
        rejected(PERMISSION_DENIED, () -> move(report, dept, C));
        rejected(ENTRY_MOVE_TO_CHILD, () -> move(dept, sub, A));
        move(note, dept, A);
        assertThat(entries.selectById(note).getParentId()).isEqualTo(dept);

        Long copied = copy(dept, team, 0L, A);
        assertThat(entries.selectById(copied).getName()).isEqualTo("部门 (2)");
        assertThat(list(team, copied, A)).containsExactly("子", "note.txt", "report (2).txt");
        rejected(PERMISSION_DENIED, () -> copy(report, team, 0L, B));
        rejected(PERMISSION_DENIED, () -> copy(report, team, dept, C));
        Long byViewer = copy(report, team, sub, B);
        assertThat(entries.selectById(byViewer).getParentId()).isEqualTo(sub);
    }

    @Test
    void trashRestoreAndPurgeFollowTheOperator() {
        long sub = folder(team, dept, "子", B);
        long inner = upload(team, sub, "inner.txt", B).getId();
        long before = usedBytes(team);

        entryService.trashEntryList(List.of(sub), B);
        assertThat(entries.selectById(inner).getTrashState()).isEqualTo("TRASHED");
        assertThat(usedBytes(team)).isEqualTo(before - 9);
        rejected(ENTRY_ALREADY_TRASHED, () -> entryService.trashEntryList(List.of(sub), B));
        rejected(PERMISSION_DENIED, () -> entryService.trashEntryList(List.of(note), B));
        rejected(PERMISSION_DENIED, () -> entryService.trashEntryList(List.of(report), C));
        entryService.trashEntryList(List.of(note), A);

        assertThat(trash(team, A)).as("空间管理者看全部删除记录").containsExactlyInAnyOrder("子", "note.txt");
        assertThat(trash(team, C)).as("其余的人只看自己删的").isEmpty();
        rejected(PERMISSION_DENIED, () -> trash(team, B));
        rejected(PERMISSION_DENIED, () -> entryService.restoreEntry(note, C));

        assertThat(entryService.restoreEntry(sub, B).getParentId()).isEqualTo(dept);
        assertThat(entries.selectById(inner).getTrashState()).isEqualTo("NORMAL");
        // 恢复的 9 字节加回来；note.txt（8 字节）还在回收站里
        assertThat(usedBytes(team)).isEqualTo(before - 8);
        rejected(ENTRY_NOT_TRASHED, () -> entryService.restoreEntry(sub, B));

        // 原位置没了：恢复到空间根
        entryService.trashEntryList(List.of(inner), A);
        entryService.trashEntryList(List.of(sub), A);
        assertThat(entryService.restoreEntry(inner, A).getParentId()).isZero();

        rejected(ENTRY_NOT_TRASHED, () -> entryService.purgeEntryList(List.of(report), A));
        entryService.purgeEntryList(List.of(sub, note), A);
        assertThat(entries.selectById(sub)).isNull();
        assertThat(entries.selectById(note)).isNull();
        assertThat(trash(team, A)).isEmpty();
    }

    @Test
    void businessSpaceStaysClosedWithoutGovernancePermission() {
        long biz = space("B-" + UUID.randomUUID(), "BIZ", null);
        long managed = bizFiles.ensureDirectory(biz, List.of("合同", "2026"), A);
        grant(biz, 0L, C, "MANAGER");
        grant(biz, managed, C, "MANAGER");

        for (long user : new long[] {A, B, C, D}) {
            assertThat(role(biz, 0L, user)).isNull();
            assertThat(role(biz, managed, user)).isNull();
            rejected(PERMISSION_DENIED, () -> list(biz, 0L, user));
            rejected(PERMISSION_DENIED, () -> list(biz, managed, user));
            // 授权表里有历史行的人过得了「可访问空间」这一关，但结果逐个按角色过滤后是空的
            if (user == C) assertThat(search(biz, "合同", user)).isEmpty();
            else rejected(PERMISSION_DENIED, () -> search(biz, "合同", user));
            rejected(PERMISSION_DENIED, () -> folder(biz, 0L, "新夹", user));
            rejected(PERMISSION_DENIED, () -> upload(biz, managed, "x.txt", user));
            rejected(PERMISSION_DENIED, () -> trash(biz, user));
            assertThat(permissionService.getAccessibleSpaceIds(user).contains(biz))
                    .as("授权表里的历史行只影响「可访问空间」清单，不给任何角色")
                    .isEqualTo(user == C);
        }
        DrivePermissionSaveReqVO save = new DrivePermissionSaveReqVO();
        save.setSpaceId(biz);
        save.setEntryId(0L);
        save.setSubjectType("USER");
        save.setSubjectId(D);
        save.setRole("VIEWER");
        rejected(SPACE_BIZ_FORBIDDEN, () -> permissionService.savePermission(save, A));
        rejected(ENTRY_MANAGED_FORBIDDEN, () -> rename(managed, "x", A));
        rejected(ENTRY_MANAGED_FORBIDDEN, () -> entryService.trashEntryList(List.of(managed), A));
    }
}

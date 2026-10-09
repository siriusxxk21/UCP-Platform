package com.richuang.os.nocode.tools;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.richuang.os.framework.common.biz.system.permission.PermissionCommonApi;
import com.richuang.os.framework.datapermission.config.OsDataPermissionAutoConfiguration;
import com.richuang.os.framework.datapermission.core.rule.DataPermissionRuleFactoryImpl;
import com.richuang.os.framework.mybatis.config.OsMybatisAutoConfiguration;
import com.richuang.os.module.drive.api.bizfile.DriveBizFileApi;
import com.richuang.os.module.drive.api.folder.DriveFolderApi;
import com.richuang.os.module.drive.dal.dataobject.entry.DriveEntryDO;
import com.richuang.os.module.drive.dal.mysql.entry.DriveEntryMapper;
import com.richuang.os.module.drive.dal.mysql.mark.DriveUserMarkMapper;
import com.richuang.os.module.drive.dal.mysql.permission.DrivePermissionMapper;
import com.richuang.os.module.drive.dal.mysql.share.DriveShareMapper;
import com.richuang.os.module.drive.dal.mysql.share.DriveShareSubjectMapper;
import com.richuang.os.module.drive.dal.mysql.space.DriveSpaceMapper;
import com.richuang.os.module.drive.service.bizfile.DriveBizFileApiImpl;
import com.richuang.os.module.drive.service.entry.DriveEntryService;
import com.richuang.os.module.drive.service.entry.DriveEntryServiceImpl;
import com.richuang.os.module.drive.service.folder.DriveFolderApiImpl;
import com.richuang.os.module.drive.service.permission.DrivePermissionService;
import com.richuang.os.module.drive.service.permission.DrivePermissionServiceImpl;
import com.richuang.os.module.drive.service.permission.DriveSubjectResolver;
import com.richuang.os.module.drive.service.space.DriveSpaceService;
import com.richuang.os.module.drive.service.space.DriveSpaceServiceImpl;
import com.richuang.os.module.drive.service.storage.DriveStorageService;
import com.richuang.os.module.infra.api.file.FileApi;
import com.richuang.os.module.infra.dal.dataobject.file.FileDO;
import com.richuang.os.module.infra.service.file.FileService;
import com.richuang.os.module.system.api.dept.DeptApi;
import com.richuang.os.module.system.api.user.AdminUserApi;

import org.mockito.Mockito;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.BeanFactoryUtils;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import javax.sql.DataSource;

/**
 * 记录文件夹用例共用的网盘装配：真实的网盘 Mapper（连测试库）与真实的 {@link DriveFolderApiImpl} / {@link DriveEntryServiceImpl}
 * / {@link DrivePermissionServiceImpl}，由 Spring 容器完成注入并让 {@code @Transactional}
 * 生效。越界与来源规则的用例必须用它，不能用网盘接口的替身。
 *
 * <p>只有三处是替身：文件内容存储（内存里的字节，不连任何对象存储）、存储源选择（恒为未指定）、用户与部门资料（恒查不到 ⇒ 没有部门链）。
 * 权限码接口在上级容器里已有时沿用上级的那个，否则自带一个替身（默认全部返回 false）。
 *
 * <p>夹具都落在名称带本次随机前缀的空间里，{@link #close()} 时按前缀清掉；测试库是本机专用的，别的用例的数据不碰。
 */
final class DriveFolderTestBed implements AutoCloseable {
    final String prefix = "__folder_bed_" + UUID.randomUUID().toString().replace("-", "");
    final AnnotationConfigApplicationContext context;
    final JdbcTemplate jdbc;
    final DriveEntryMapper entries;
    final DriveSpaceMapper spaces;
    final DriveEntryService entryService;
    final DrivePermissionService permissionService;
    final DriveSpaceService spaceService;

    /** 真实的网盘侧实现：网盘侧的用例直接调它。 */
    final DriveFolderApi folders;

    /** 网盘接口的开关：无代码侧的组件注入到的是它（主候选）；默认原样转给真实实现。 */
    final Switch drive = new Switch();

    final DriveBizFileApi bizFiles;
    final PermissionCommonApi permissionApi;
    final Files files = new Files();

    /** 内存里的文件内容；编号从一个很大的数起步，不与测试库里已有的 infra_file 撞号。 */
    static final class Files {
        final Map<Long, byte[]> contents = new ConcurrentHashMap<>();
        private final AtomicLong next = new AtomicLong(8_800_000_000L);

        FileDO store(InputStream content, String name, String type) {
            try {
                return store(content.readAllBytes(), name, type);
            } catch (IOException error) {
                throw new UncheckedIOException(error);
            }
        }

        FileDO store(byte[] bytes, String name, String type) {
            long id = next.incrementAndGet();
            contents.put(id, bytes);
            FileDO file = new FileDO();
            file.setId(id);
            file.setName(name);
            file.setType(type);
            file.setSize((long) bytes.length);
            file.setProtectedFlag(Boolean.TRUE);
            return file;
        }
    }

    /** 让用例能把网盘侧换成「恒抛异常」「建目录时先卡住」「某个名字建不出来」，并看到建目录发生在哪条线程上。 */
    static final class Switch implements java.lang.reflect.InvocationHandler {
        private volatile DriveFolderApi real;

        /** 非空时每个调用都抛它 */
        volatile RuntimeException failure;

        /** 非空时建目录进来先等它放行 */
        volatile java.util.concurrent.CountDownLatch hold;

        /** 非空时名字相同的那个目录建不出来 */
        volatile String refusedName;

        final List<String> ensureThreads = new java.util.concurrent.CopyOnWriteArrayList<>();
        final java.util.concurrent.atomic.AtomicInteger calls =
                new java.util.concurrent.atomic.AtomicInteger();

        void reset() {
            failure = null;
            hold = null;
            refusedName = null;
            ensureThreads.clear();
            calls.set(0);
        }

        @Override
        public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] args)
                throws Throwable {
            if (method.getDeclaringClass() == Object.class) return method.invoke(this, args);
            calls.incrementAndGet();
            RuntimeException failing = failure;
            if (failing != null) throw failing;
            if (method.getName().equals("ensureChild")) {
                ensureThreads.add(Thread.currentThread().getName());
                java.util.concurrent.CountDownLatch waiting = hold;
                if (waiting != null) waiting.await();
                if (args[1] != null && args[1].equals(refusedName))
                    throw new IllegalStateException("网盘侧故障（用例注入）");
            }
            try {
                return method.invoke(real, args);
            } catch (java.lang.reflect.InvocationTargetException wrapped) {
                throw wrapped.getCause();
            }
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement(proxyTargetClass = true)
    @Import({
        DriveSubjectResolver.class,
        DrivePermissionServiceImpl.class,
        DriveSpaceServiceImpl.class,
        DriveEntryServiceImpl.class,
        DriveBizFileApiImpl.class,
        DriveFolderApiImpl.class
    })
    static class Beans {}

    private DriveFolderTestBed(
            ApplicationContext parent,
            java.util.function.Consumer<AnnotationConfigApplicationContext> customize)
            throws Exception {
        jdbc = parent.getBean(JdbcTemplate.class);
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        // 与正式配置一致：没有声明主键策略的 DO（网盘各表）由 MyBatis-Plus 预先分配雪花编号。
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
        // 与正式启动一样保留数据权限拦截器：新加的手写语句（含递归查询）要经得住它的解析。
        new OsDataPermissionAutoConfiguration()
                .dataPermissionRuleHandler(
                        interceptors, new DataPermissionRuleFactoryImpl(List.of()));
        MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(parent.getBean(DataSource.class));
        factory.setConfiguration(configuration);
        factory.setGlobalConfig(globalConfig);
        factory.setMapperLocations(
                new PathMatchingResourcePatternResolver()
                        .getResources("classpath*:mapper/drive/*.xml"));
        factory.setPlugins(interceptors);
        SqlSessionTemplate session = new SqlSessionTemplate(factory.getObject());

        context = new AnnotationConfigApplicationContext();
        context.setParent(parent);
        for (Class<?> mapper : mappers) registerMapper(session, mapper);
        if (missing(parent, PermissionCommonApi.class))
            context.registerBean(
                    PermissionCommonApi.class, () -> Mockito.mock(PermissionCommonApi.class));
        if (missing(parent, AdminUserApi.class))
            context.registerBean(AdminUserApi.class, () -> Mockito.mock(AdminUserApi.class));
        if (missing(parent, DeptApi.class))
            context.registerBean(DeptApi.class, () -> Mockito.mock(DeptApi.class));
        if (missing(parent, FileService.class))
            context.registerBean(FileService.class, () -> Mockito.mock(FileService.class));
        context.registerBean(FileApi.class, this::fileApi);
        context.registerBean(DriveStorageService.class, DriveFolderTestBed::storage);
        context.registerBean(
                "driveFolderApiSwitch",
                DriveFolderApi.class,
                () ->
                        (DriveFolderApi)
                                java.lang.reflect.Proxy.newProxyInstance(
                                        DriveFolderApi.class.getClassLoader(),
                                        new Class<?>[] {DriveFolderApi.class},
                                        drive),
                definition -> definition.setPrimary(true));
        context.register(Beans.class);
        customize.accept(context);
        context.refresh();

        entries = context.getBean(DriveEntryMapper.class);
        spaces = context.getBean(DriveSpaceMapper.class);
        entryService = context.getBean(DriveEntryService.class);
        permissionService = context.getBean(DrivePermissionService.class);
        spaceService = context.getBean(DriveSpaceService.class);
        folders = context.getBean(DriveFolderApiImpl.class);
        drive.real = folders;
        bizFiles = context.getBean(DriveBizFileApi.class);
        permissionApi = context.getBean(PermissionCommonApi.class);
    }

    static DriveFolderTestBed open(ApplicationContext parent) {
        return open(parent, context -> {});
    }

    /** customize：刷新之前往这个容器里再登记组件（无代码侧的文件夹服务等）。 */
    static DriveFolderTestBed open(
            ApplicationContext parent,
            java.util.function.Consumer<AnnotationConfigApplicationContext> customize) {
        try {
            return new DriveFolderTestBed(parent, customize);
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    private static boolean missing(ApplicationContext parent, Class<?> type) {
        return BeanFactoryUtils.beanNamesForTypeIncludingAncestors(parent, type).length == 0;
    }

    @SuppressWarnings("unchecked")
    private <T> void registerMapper(SqlSessionTemplate session, Class<?> type) {
        Class<T> mapper = (Class<T>) type;
        context.registerBean(mapper, () -> session.getMapper(mapper));
    }

    /** 存储源恒为未指定：内容走默认的那个上传入口（替身对 Long 的缺省返回值是 0 而不是空，要显式给）。 */
    private static DriveStorageService storage() {
        DriveStorageService storage = Mockito.mock(DriveStorageService.class);
        Mockito.when(storage.getSelectedConfigId()).thenReturn(null);
        return storage;
    }

    private FileApi fileApi() {
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
                                files.store(
                                        call.<InputStream>getArgument(0),
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
                                files.store(
                                        call.<byte[]>getArgument(0),
                                        call.getArgument(1),
                                        call.getArgument(3)));
        Mockito.when(api.getFileContent(Mockito.anyLong()))
                .thenAnswer(call -> files.contents.get(call.<Long>getArgument(0)));
        Mockito.when(api.getFileContentLength(Mockito.anyLong()))
                .thenAnswer(
                        call -> {
                            byte[] bytes = files.contents.get(call.<Long>getArgument(0));
                            return bytes == null ? null : (long) bytes.length;
                        });
        Mockito.when(api.getFileContentStream(Mockito.anyLong(), Mockito.anyLong()))
                .thenAnswer(
                        call -> {
                            byte[] bytes = files.contents.get(call.<Long>getArgument(0));
                            if (bytes == null) return null;
                            int offset = (int) Math.min(call.<Long>getArgument(1), bytes.length);
                            return new ByteArrayInputStream(bytes, offset, bytes.length - offset);
                        });
        return api;
    }

    // ========== 夹具 ==========

    /** type：TEAM / BIZ / PERSONAL；owner 可空（业务空间没有归属人）。 */
    long space(String name, String type, Long owner) {
        return jdbc.queryForObject(
                "INSERT INTO public.drive_space(name,type,owner_id) VALUES (?,?,?) RETURNING id",
                Long.class,
                prefix + name,
                type,
                owner);
    }

    /** 普通目录：走网盘自己的新建入口，actor 必须在父目录上有网盘的可编辑角色。 */
    long folder(long space, long parent, String name, long actor) {
        var request =
                new com.richuang.os.module.drive.controller.admin.entry.vo
                        .DriveEntryFolderCreateReqVO();
        request.setSpaceId(space);
        request.setParentId(parent);
        request.setName(name);
        return entryService.createFolder(request, actor);
    }

    /** 普通文件：走网盘自己的上传入口，内容就是文件名的字节。 */
    long file(long space, long parent, String name, long actor) {
        byte[] bytes = name.getBytes(StandardCharsets.UTF_8);
        return entryService
                .uploadFile(
                        space,
                        parent,
                        name,
                        "text/plain",
                        bytes.length,
                        new ByteArrayInputStream(bytes),
                        actor)
                .getId();
    }

    /** 直接插表的网盘授权行（对用户、含下级）。 */
    void grant(long space, long entry, long user, String role) {
        jdbc.update(
                "INSERT INTO"
                        + " public.drive_permission(space_id,entry_id,subject_type,subject_id,role)"
                        + " VALUES (?,?,'USER',?,?)",
                space,
                entry,
                user,
                role);
    }

    void mark(String originKey, Long... ids) {
        entries.insertOrigins(List.of(ids), originKey, 0L);
    }

    /** 节点的来源键；没有标记返回 null。 */
    String originOf(long entry) {
        return jdbc
                .query(
                        "SELECT origin_key FROM public.drive_entry_origin WHERE entry_id=?",
                        (row, index) -> row.getString(1),
                        entry)
                .stream()
                .findFirst()
                .orElse(null);
    }

    DriveEntryDO entry(long id) {
        return entries.selectById(id);
    }

    long usedBytes(long space) {
        return jdbc.queryForObject(
                "SELECT used_bytes FROM public.drive_space WHERE id=?", Long.class, space);
    }

    @Override
    public void close() {
        try {
            String like = prefix + "%";
            String owned = "(SELECT id FROM public.drive_space WHERE name LIKE ?)";
            jdbc.update(
                    "DELETE FROM public.drive_entry_origin WHERE entry_id IN (SELECT id FROM"
                            + " public.drive_entry WHERE space_id IN "
                            + owned
                            + ")",
                    like);
            jdbc.update("DELETE FROM public.drive_permission WHERE space_id IN " + owned, like);
            jdbc.update("DELETE FROM public.drive_entry WHERE space_id IN " + owned, like);
            jdbc.update("DELETE FROM public.drive_space WHERE name LIKE ?", like);
        } finally {
            context.close();
        }
    }
}

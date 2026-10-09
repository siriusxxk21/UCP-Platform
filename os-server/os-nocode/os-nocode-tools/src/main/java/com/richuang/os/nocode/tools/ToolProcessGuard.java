package com.richuang.os.nocode.tools;

import org.aopalliance.intercept.MethodInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.aop.framework.autoproxy.AbstractBeanFactoryAwareAdvisingPostProcessor;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.aop.support.StaticMethodMatcherPointcut;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.Ordered;
import org.springframework.core.env.MapPropertySource;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.scheduling.config.TaskManagementConfigUtils;
import org.springframework.util.ClassUtils;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.regex.Pattern;

/**
 * 工具进程只做前台工作：拉起完整业务装配时不启动任何后台作业（设计稿 9.5）。
 *
 * <p>线上执行工具时，工具进程与在线服务共用数据库；后台作业会抢占待办处理请求和流程作业，启动任务还会写库。 本类只作用于工具自己创建的
 * SpringApplication，不改正常服务的组件与配置：
 *
 * <ol>
 *   <li>以最高优先级的属性源强制 {@link #FORCED}：{@link #NO_BACKGROUND_JOBS} 与 {@link
 *       #MIGRATION_PROTECTION}；外置配置文件与 -D 都覆盖不了；
 *   <li>移除 {@code @EnableScheduling} 注册的定时任务处理器，{@code @Scheduled} 方法一个都不调度；
 *   <li>拦截所有 ApplicationRunner / CommandLineRunner 的 run（启动横幅、消息类型注册、字典菜单注册、消息发送队列等）， bean
 *       照常创建，其它方法照常可用；
 *   <li>上下文起来后自检（{@link #verify}）：没有后台作业线程、没有已调度的任务、启动任务全部被拦截；不通过就关闭上下文并失败， 不做任何业务读写。
 * </ol>
 */
final class ToolProcessGuard {
    private static final Logger log = LoggerFactory.getLogger(ToolProcessGuard.class);

    static final String PROPERTY_SOURCE = "objectRuleMigrationToolGuard";

    /** 关闭后台作业的属性：Flowable 异步执行器、办理请求定时补偿、PowerJob；不启动 Web、不打印横幅。 */
    static final Map<String, Object> NO_BACKGROUND_JOBS =
            Map.of(
                    "flowable.async-executor-activate", "false",
                    "flowable.async-history-executor-activate", "false",
                    "nocode.handling.reconcile-enabled", "false",
                    "powerjob.worker.enabled", "false",
                    "powerjob.client.enabled", "false",
                    "spring.main.web-application-type", "none",
                    "spring.main.banner-mode", "off");

    /** 与线上启动（deploy/deploy.sh）相同的 7 条迁移保护开关：启动时不建表、不补数、不跑任何自动迁移。 */
    static final Map<String, Object> MIGRATION_PROTECTION =
            Map.of(
                    "app.database.init-enabled", "false",
                    "app.user-pinyin.backfill-enabled", "false",
                    "spring.sql.init.mode", "never",
                    "spring.flyway.enabled", "false",
                    "spring.liquibase.enabled", "false",
                    "spring.jpa.hibernate.ddl-auto", "none",
                    "flowable.database-schema-update", "false");

    /** 工具进程强制生效的全部属性。 */
    static final Map<String, Object> FORCED = forced();

    private static Map<String, Object> forced() {
        var all = new TreeMap<String, Object>(NO_BACKGROUND_JOBS);
        all.putAll(MIGRATION_PROTECTION);
        return Collections.unmodifiableMap(all);
    }

    /** 后台作业线程：Spring 定时任务、Flowable 作业获取/执行、消息发送队列消费者。 */
    static final Pattern BACKGROUND_THREAD =
            Pattern.compile(
                    "scheduling-\\d+"
                            + "|flowable-.*(acquire|reset-expired|async-job|async-history).*"
                            + "|Thread-SYS_MSG_SEND_QUEUE-CONSUMER");

    private static final String RUNNER_GUARD_BEAN = "objectRuleMigrationToolRunnerGuard";

    /** 自检结果：runnerBeans 为容器内启动任务 bean，skippedRunners 为被拦截的实例数。 */
    record Verification(List<String> runnerBeans, int skippedRunners) {}

    private final RunnerGuard runners = new RunnerGuard();

    ToolProcessGuard() {}

    /** 按工具模式启动并自检；自检不通过时关闭上下文并抛出。 */
    static ConfigurableApplicationContext start(SpringApplicationBuilder application) {
        var guard = new ToolProcessGuard();
        var context =
                application
                        .listeners(new ForcedProperties())
                        .initializers(guard.new Disable())
                        .run();
        try {
            var result = guard.verify(context);
            log.info("工具进程自检通过：无后台作业线程、无已调度任务，{} 个启动任务均已拦截未执行", result.skippedRunners());
            return context;
        } catch (RuntimeException e) {
            context.close();
            throw e;
        }
    }

    /** 上下文起来之后（启动任务已被 Spring Boot 调用过）核对；有问题直接抛出。 */
    Verification verify(ConfigurableApplicationContext context) {
        var problems = new ArrayList<String>();
        var factory = context.getBeanFactory();
        if (factory.containsBeanDefinition(
                TaskManagementConfigUtils.SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME))
            problems.add("定时任务处理器仍在容器中");
        for (var name : factory.getBeanNamesForType(ScheduledTaskHolder.class, true, false)) {
            if (factory.containsSingleton(name)
                    && factory.getBean(name) instanceof ScheduledTaskHolder holder
                    && !holder.getScheduledTasks().isEmpty())
                problems.add("已调度的任务：" + holder.getScheduledTasks());
        }
        var runnerBeans = new TreeSet<String>();
        runnerBeans.addAll(List.of(factory.getBeanNamesForType(ApplicationRunner.class)));
        runnerBeans.addAll(List.of(factory.getBeanNamesForType(CommandLineRunner.class)));
        int skipped = runners.skipped();
        if (skipped != runnerBeans.size())
            problems.add("启动任务 " + runnerBeans.size() + " 个，只拦截到 " + skipped + " 个：" + runnerBeans);
        var threads = backgroundThreads();
        if (!threads.isEmpty()) problems.add("后台作业线程：" + threads);
        if (!problems.isEmpty()) throw new IllegalStateException("工具进程自检失败：" + problems);
        return new Verification(List.copyOf(runnerBeans), skipped);
    }

    static List<String> backgroundThreads() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(Thread::isAlive)
                .map(Thread::getName)
                .filter(name -> BACKGROUND_THREAD.matcher(name).matches())
                .sorted()
                .toList();
    }

    /** 放在配置文件与 -D 之前，任何外部配置都覆盖不了。 */
    static final class ForcedProperties
            implements ApplicationListener<ApplicationEnvironmentPreparedEvent>, Ordered {
        @Override
        public void onApplicationEvent(ApplicationEnvironmentPreparedEvent event) {
            event.getEnvironment()
                    .getPropertySources()
                    .addFirst(new MapPropertySource(PROPERTY_SOURCE, FORCED));
        }

        @Override
        public int getOrder() {
            return Ordered.LOWEST_PRECEDENCE;
        }
    }

    /** 配置类处理完之后执行：移除定时任务处理器，登记启动任务拦截器。 */
    private final class Disable
            implements ApplicationContextInitializer<ConfigurableApplicationContext>,
                    BeanFactoryPostProcessor {
        @Override
        public void initialize(ConfigurableApplicationContext context) {
            context.addBeanFactoryPostProcessor(this);
        }

        @Override
        public void postProcessBeanFactory(ConfigurableListableBeanFactory factory) {
            var registry = (BeanDefinitionRegistry) factory;
            var scheduling = TaskManagementConfigUtils.SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME;
            if (registry.containsBeanDefinition(scheduling))
                registry.removeBeanDefinition(scheduling);
            var definition = new RootBeanDefinition(RunnerGuard.class, () -> runners);
            definition.setRole(BeanDefinition.ROLE_INFRASTRUCTURE);
            registry.registerBeanDefinition(RUNNER_GUARD_BEAN, definition);
        }
    }

    /**
     * 给启动任务的 run 方法加一个空实现的切面：已有代理的 bean 追加切面，其余按类生成代理（lambda 或 final 类按接口代理）。 排在所有代理处理器之后，与
     * {@code @Async} 的处理方式相同。
     */
    static final class RunnerGuard extends AbstractBeanFactoryAwareAdvisingPostProcessor {
        private final Set<Object> skipped = Collections.newSetFromMap(new IdentityHashMap<>());

        RunnerGuard() {
            setProxyTargetClass(true);
            setBeforeExistingAdvisors(true);
            MethodInterceptor skip =
                    invocation -> {
                        synchronized (skipped) {
                            skipped.add(invocation.getThis());
                        }
                        return null;
                    };
            this.advisor = new DefaultPointcutAdvisor(new RunMethods(), skip);
        }

        int skipped() {
            synchronized (skipped) {
                return skipped.size();
            }
        }

        @Override
        protected ProxyFactory prepareProxyFactory(Object bean, String beanName) {
            var factory = super.prepareProxyFactory(bean, beanName);
            var type = bean.getClass();
            if (ClassUtils.isLambdaClass(type) || Modifier.isFinal(type.getModifiers()))
                factory.setProxyTargetClass(false);
            return factory;
        }
    }

    /** ApplicationRunner.run(ApplicationArguments) 与 CommandLineRunner.run(String...)。 */
    static final class RunMethods extends StaticMethodMatcherPointcut {
        RunMethods() {
            setClassFilter(
                    type ->
                            ApplicationRunner.class.isAssignableFrom(type)
                                    || CommandLineRunner.class.isAssignableFrom(type));
        }

        @Override
        public boolean matches(Method method, Class<?> targetClass) {
            if (!"run".equals(method.getName()) || method.getParameterCount() != 1) return false;
            var parameter = method.getParameterTypes()[0];
            return parameter == ApplicationArguments.class
                            && ApplicationRunner.class.isAssignableFrom(targetClass)
                    || parameter == String[].class
                            && CommandLineRunner.class.isAssignableFrom(targetClass);
        }
    }
}

package com.richuang.os.nocode.runtime.service.folder;

import com.richuang.os.framework.security.core.util.SecurityFrameworkUtils;
import com.richuang.os.module.drive.api.folder.DriveFolderApi;
import com.richuang.os.nocode.api.ApplicationRecords.Row;
import com.richuang.os.nocode.api.RecordFolders;
import com.richuang.os.nocode.runtime.dal.dataobject.RecordFolderSourceDO;
import com.richuang.os.nocode.runtime.dal.mapper.RecordFolderSourceMapper;
import com.richuang.os.nocode.runtime.service.live.RecordChangeBatch;
import com.richuang.os.nocode.runtime.service.live.RecordChangeListener;
import com.richuang.os.nocode.runtime.service.record.RecordQueryAccess;

import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 「记录保存时就建文件夹」的后台建立：记录变更在事务提交之后交到这里，这里只入队就返回；一条后台线程逐个取出，为记录把还没建的子文件夹建出来。
 *
 * <p>不在保存事务里做任何事，建失败不影响保存：建目录发生在提交之后、另一条线程上，任何异常只记日志。没建成的记录仍是「还没建」，打开记录、
 * 第一次放东西、配置界面的补建三道兜底任何一道都会把它建出来。队列满或进程重启丢掉的任务同样靠兜底。
 *
 * <p>所有协作者都延迟获取：网盘接口不存在的进程（没有装配网盘实现）里这个组件静默不工作，不因为它让任何进程起不来。
 */
@Component
public class RecordFolderAutoCreator implements RecordChangeListener {
    public static final int QUEUE_CAPACITY = 5000;
    public static final int SWEEP_PAGE = 100;
    public static final long SWEEP_PAUSE_MILLIS = 20;
    public static final long SOURCE_CACHE_MILLIS = 60_000;
    public static final long SHUTDOWN_MILLIS = 2_000;

    private static final Logger log = LoggerFactory.getLogger(RecordFolderAutoCreator.class);

    @Resource private ObjectProvider<DriveFolderApi> driveFolders;
    @Resource private ObjectProvider<RecordFolderSourceMapper> sources;
    @Resource private ObjectProvider<RecordQueryAccess> records;
    @Resource private ObjectProvider<RecordFolderResolver> resolver;
    @Resource private ObjectProvider<RecordFolderConfigService> configs;

    private sealed interface Task permits One, Sweep {
        String objectId();
    }

    /** 一条记录：为它把「保存时就建」的来源里还没建的子文件夹建出来 */
    private record One(String objectId, String recordId, long actor) implements Task {}

    /** 整对象补扫：一个事务里变更太多、拿不到逐条编号时，分页扫全表补建 */
    private record Sweep(String objectId) implements Task {}

    private record Cached(boolean onSave, long expiresAt) {}

    private final BlockingQueue<Task> queue;
    private final Map<String, Cached> onSaveObjects = new ConcurrentHashMap<>();
    private final Set<String> sweeping = ConcurrentHashMap.newKeySet();
    private final AtomicInteger outstanding = new AtomicInteger();
    private final AtomicLong enqueued = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong lookups = new AtomicLong();
    private final Object lifecycle = new Object();
    private volatile boolean closing;
    private volatile Thread thread;

    public RecordFolderAutoCreator() {
        this(QUEUE_CAPACITY);
    }

    /** 指定队列容量（测试用：验证队列满时不阻塞、不抛出）。 */
    public RecordFolderAutoCreator(int capacity) {
        queue = new ArrayBlockingQueue<>(capacity);
    }

    /** 在提交事务的线程上被调用：只入队，立即返回；任何情况下不阻塞、不抛出。 */
    @Override
    public void committed(RecordChangeBatch batch) {
        try {
            if (closing || batch == null || driveFolders.getIfAvailable() == null) return;
            Long login = SecurityFrameworkUtils.getLoginUserId();
            long actor = login == null ? 0L : login;
            for (RecordChangeBatch.ObjectChange change : batch.objects()) {
                if (!hasOnSaveSource(change.objectId())) continue;
                if (change.many()) {
                    // 同一对象已有未处理的整对象补扫时不重复入队
                    if (sweeping.add(change.objectId()) && !offer(new Sweep(change.objectId())))
                        sweeping.remove(change.objectId());
                    continue;
                }
                // 删除的不处理；新建与修改都处理：新建时关联还空着的记录，之后填上关联再保存也会建
                for (String recordId : change.created())
                    offer(new One(change.objectId(), recordId, actor));
                for (String recordId : change.updated())
                    offer(new One(change.objectId(), recordId, actor));
            }
        } catch (Throwable error) {
            log.warn("记录文件夹后台建立入队失败，已忽略", error);
        }
    }

    /** /open 发现「保存时就建」的来源仍是还没建时调用：入队，立即返回 */
    public void request(String objectId, String recordId) {
        try {
            if (closing || driveFolders.getIfAvailable() == null) return;
            offer(new One(objectId, recordId, 0L));
        } catch (Throwable error) {
            log.warn("记录文件夹后台建立入队失败，已忽略", error);
        }
    }

    /** 配置保存后调用：清掉这个对象的「有没有保存时就建的来源」缓存 */
    public void forget(String objectId) {
        onSaveObjects.remove(objectId);
    }

    /** 测试用：等队列处理完，最多等给定毫秒数；返回是否处理完 */
    public boolean drain(long millis) {
        long deadline = System.currentTimeMillis() + millis;
        while (outstanding.get() > 0) {
            if (System.currentTimeMillis() >= deadline) return false;
            try {
                Thread.sleep(5);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return true;
    }

    /** 测试用：累计入过队的任务数 */
    public long enqueued() {
        return enqueued.get();
    }

    /** 测试用：因队列满被丢弃的任务数 */
    public long dropped() {
        return dropped.get();
    }

    /** 测试用：「这个对象有没有保存时就建的来源」查过几次库 */
    public long lookups() {
        return lookups.get();
    }

    /** 这个对象有没有未删除的「保存时就建」来源；查库结果缓存一分钟。提交线程上唯一一次可能的数据库访问。 */
    private boolean hasOnSaveSource(String objectId) {
        long now = System.currentTimeMillis();
        Cached cached = onSaveObjects.get(objectId);
        if (cached != null && cached.expiresAt() > now) return cached.onSave();
        lookups.incrementAndGet();
        boolean onSave = !sources.getObject().selectOnSave(objectId).isEmpty();
        onSaveObjects.put(objectId, new Cached(onSave, now + SOURCE_CACHE_MILLIS));
        return onSave;
    }

    private boolean offer(Task task) {
        // 先计数再入队：后台线程取走任务时计数已经在了，等待处理完的一方不会看到「还没入账」的空档
        outstanding.incrementAndGet();
        if (!queue.offer(task)) {
            outstanding.decrementAndGet();
            dropped.incrementAndGet();
            log.warn("记录文件夹后台建立队列已满，丢弃任务（靠打开记录、第一次写入或补建兜底）: object={}", task.objectId());
            return false;
        }
        enqueued.incrementAndGet();
        if (thread == null) start();
        return true;
    }

    private void start() {
        synchronized (lifecycle) {
            if (thread != null || closing) return;
            Thread worker = new Thread(this::run, "nocode-record-folder");
            worker.setDaemon(true);
            thread = worker;
            worker.start();
        }
    }

    private void run() {
        while (!closing) {
            Task task;
            try {
                task = queue.take();
            } catch (InterruptedException interrupted) {
                // 关闭时用中断唤醒；循环顶部看到 closing 后退出
                continue;
            }
            try {
                if (task instanceof One one) process(one);
                else if (task instanceof Sweep sweep) process(sweep);
            } catch (Throwable error) {
                log.warn("记录文件夹后台建立失败，已忽略（记录仍可在打开、第一次写入或补建时建立）: task={}", task, error);
            } finally {
                outstanding.decrementAndGet();
            }
        }
    }

    private void process(One task) {
        List<RecordFolderSourceDO> onSave = sources.getObject().selectOnSave(task.objectId());
        if (onSave.isEmpty()) return;
        Row stored = records.getObject().storedRow(task.objectId(), task.recordId(), task.actor());
        if (stored == null) return;
        RecordFolderResolver folders = resolver.getObject();
        List<Throwable> failures = new ArrayList<>();
        for (RecordFolderSourceDO source : onSave) {
            try {
                // 只建还没建的；其它状态（已有、没选关联、不可用）不动
                if (folders.resolve(source, task.objectId(), task.recordId(), stored, task.actor())
                        .pending())
                    folders.ensure(source, task.objectId(), task.recordId(), stored, task.actor());
            } catch (RuntimeException error) {
                failures.add(error);
            }
        }
        if (!failures.isEmpty()) {
            IllegalStateException summary = new IllegalStateException("部分文件夹来源建立失败");
            failures.forEach(summary::addSuppressed);
            throw summary;
        }
    }

    private void process(Sweep task) throws InterruptedException {
        // 取出即摘除标记：处理期间又来的大批变更可以再排一个补扫
        sweeping.remove(task.objectId());
        RecordFolderConfigService pages = configs.getObject();
        for (RecordFolderSourceDO source : sources.getObject().selectOnSave(task.objectId())) {
            String cursor = null;
            while (!closing) {
                RecordFolders.BackfillResult page = pages.page(source, cursor, SWEEP_PAGE, 0L);
                if (page.done()) break;
                cursor = page.cursor();
                Thread.sleep(SWEEP_PAUSE_MILLIS);
            }
        }
    }

    /** 容器关闭：停止接收并结束后台线程，最长等 2 秒；没处理完的任务靠兜底。 */
    @PreDestroy
    public void close() {
        Thread worker;
        synchronized (lifecycle) {
            if (closing) return;
            closing = true;
            worker = thread;
        }
        if (worker == null) return;
        worker.interrupt();
        try {
            worker.join(SHUTDOWN_MILLIS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        if (worker.isAlive()) log.warn("记录文件夹后台建立线程未在 {} 毫秒内结束", SHUTDOWN_MILLIS);
    }
}

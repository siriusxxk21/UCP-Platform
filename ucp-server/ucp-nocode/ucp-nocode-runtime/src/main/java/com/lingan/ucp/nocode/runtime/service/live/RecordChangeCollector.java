package com.lingan.ucp.nocode.runtime.service.live;

import com.lingan.ucp.nocode.enums.RecordChangeOperationEnum;

import jakarta.annotation.Resource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 记录变更登记簿：写点在物理写成功之后登记，同一事务的登记合并成一份，事务提交之后才交给监听者；回滚不交付。
 *
 * <p>登记簿绑在当前事务上；内层独立事务（REQUIRES_NEW）挂起外层时登记簿一并解绑，内外两本互不串。交付在提交事务的线程上同步进行，
 * 此刻数据已提交：监听者的任何异常都只记日志，绝不离开交付过程，否则调用方会把已成功的保存当成失败。
 */
@Component
public class RecordChangeCollector {
    /** 同一对象在一本登记簿里的记录数上限；超过即不再逐条列出。 */
    public static final int ID_CAP = 200;

    private static final Logger log = LoggerFactory.getLogger(RecordChangeCollector.class);

    @Resource private ObjectProvider<RecordChangeListener> changeListeners;

    private final List<RecordChangeListener> registered = new CopyOnWriteArrayList<>();
    private volatile List<RecordChangeListener> resolved;

    /** 在物理写成功之后调用。operation 只接受 CREATE / UPDATE / DELETE。 */
    public void changed(String objectId, String recordId, RecordChangeOperationEnum operation) {
        if (objectId == null || objectId.isBlank() || recordId == null || recordId.isBlank())
            throw new IllegalArgumentException("登记记录变更需要对象标识和记录标识");
        if (operation != RecordChangeOperationEnum.CREATE
                && operation != RecordChangeOperationEnum.UPDATE
                && operation != RecordChangeOperationEnum.DELETE)
            throw new IllegalArgumentException("登记记录变更只接受 CREATE / UPDATE / DELETE");
        Ledger ledger = ledger();
        if (ledger == null) {
            // 没有事务：主代码里不应出现，留作防御，直接交付只含这一条的批。
            Ledger single = new Ledger();
            single.changed(objectId, recordId, operation);
            deliver(single.freeze());
            return;
        }
        ledger.changed(objectId, recordId, operation);
    }

    /** 不知道或无法逐条列出哪些记录（整列改写）。 */
    public void bulk(String objectId) {
        if (objectId == null || objectId.isBlank())
            throw new IllegalArgumentException("登记记录变更需要对象标识");
        Ledger ledger = ledger();
        if (ledger == null) {
            Ledger single = new Ledger();
            single.bulk(objectId);
            deliver(single.freeze());
            return;
        }
        ledger.bulk(objectId);
    }

    /** 程序化登记监听者（测试、以后的缓存失效）；返回的句柄 close 后解除。 */
    public AutoCloseable listen(RecordChangeListener listener) {
        if (listener == null) throw new IllegalArgumentException("监听者不能为空");
        registered.add(listener);
        return () -> registered.remove(listener);
    }

    /** 当前事务的登记簿；第一次登记时建簿、绑到事务并注册唯一的同步回调。没有活动的事务同步时返回 null。 */
    private Ledger ledger() {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) return null;
        if (TransactionSynchronizationManager.getResource(this) instanceof Ledger bound)
            // 已交付过的登记簿不再接收：提交回调之后的写入已不属于这次提交，按没有事务处理。
            return bound.delivered ? null : bound;
        Ledger created = new Ledger();
        TransactionSynchronizationManager.bindResource(this, created);
        TransactionSynchronizationManager.registerSynchronization(new Delivery(created));
        return created;
    }

    private void deliver(RecordChangeBatch batch) {
        if (batch.objects().isEmpty()) return;
        for (RecordChangeListener listener : listeners()) {
            try {
                listener.committed(batch);
            } catch (Throwable error) {
                log.warn("记录变更监听者执行失败，已忽略: listener={}", listener.getClass().getName(), error);
            }
        }
    }

    private List<RecordChangeListener> listeners() {
        List<RecordChangeListener> fromBeans = resolved;
        if (fromBeans == null) {
            // 首次交付时解析一次并缓存；一个都没有也正常。解析本身失败不影响已提交的业务，下次交付再试。
            try {
                fromBeans =
                        changeListeners == null
                                ? List.of()
                                : changeListeners.orderedStream().toList();
                resolved = fromBeans;
            } catch (Throwable error) {
                log.warn("记录变更监听者解析失败，本次只交付给程序化登记的监听者", error);
                fromBeans = List.of();
            }
        }
        if (registered.isEmpty()) return fromBeans;
        List<RecordChangeListener> all = new ArrayList<>(fromBeans);
        all.addAll(registered);
        return all;
    }

    /** 绑在一个事务上的同步回调：挂起时解绑、恢复时重新绑上、提交后交付、结束时解绑。 */
    private final class Delivery implements TransactionSynchronization {
        private final Ledger ledger;

        private Delivery(Ledger ledger) {
            this.ledger = ledger;
        }

        @Override
        public void suspend() {
            TransactionSynchronizationManager.unbindResourceIfPossible(RecordChangeCollector.this);
        }

        @Override
        public void resume() {
            TransactionSynchronizationManager.bindResource(RecordChangeCollector.this, ledger);
        }

        @Override
        public void afterCommit() {
            ledger.delivered = true;
            try {
                deliver(ledger.freeze());
            } catch (Throwable error) {
                log.warn("记录变更交付失败，已忽略", error);
            }
        }

        @Override
        public void afterCompletion(int status) {
            ledger.delivered = true;
            TransactionSynchronizationManager.unbindResourceIfPossible(RecordChangeCollector.this);
        }
    }

    /** 一个事务内的登记簿：按对象、按记录合并。只在所属事务的线程上访问。 */
    private static final class Ledger {
        private final Map<String, Entry> objects = new LinkedHashMap<>();
        private boolean delivered;

        void changed(String objectId, String recordId, RecordChangeOperationEnum operation) {
            Entry entry = objects.computeIfAbsent(objectId, key -> new Entry());
            if (entry.many) return;
            switch (operation) {
                case CREATE -> {
                    if (entry.deleted.remove(recordId)) entry.updated.add(recordId);
                    else if (!entry.updated.contains(recordId)) entry.created.add(recordId);
                }
                case UPDATE -> {
                    if (entry.deleted.remove(recordId)) entry.updated.add(recordId);
                    else if (!entry.created.contains(recordId)) entry.updated.add(recordId);
                }
                case DELETE -> {
                    if (entry.created.remove(recordId)) return;
                    entry.updated.remove(recordId);
                    entry.deleted.add(recordId);
                }
                default -> throw new IllegalArgumentException("登记记录变更只接受 CREATE / UPDATE / DELETE");
            }
            if (entry.created.size() + entry.updated.size() + entry.deleted.size() > ID_CAP)
                entry.toMany();
        }

        void bulk(String objectId) {
            objects.computeIfAbsent(objectId, key -> new Entry()).toMany();
        }

        RecordChangeBatch freeze() {
            List<RecordChangeBatch.ObjectChange> changes = new ArrayList<>();
            objects.forEach(
                    (objectId, entry) -> {
                        if (!entry.many
                                && entry.created.isEmpty()
                                && entry.updated.isEmpty()
                                && entry.deleted.isEmpty()) return;
                        changes.add(
                                new RecordChangeBatch.ObjectChange(
                                        objectId,
                                        entry.many,
                                        List.copyOf(entry.created),
                                        List.copyOf(entry.updated),
                                        List.copyOf(entry.deleted)));
                    });
            return new RecordChangeBatch(changes);
        }
    }

    private static final class Entry {
        private boolean many;
        private Set<String> created = new LinkedHashSet<>();
        private Set<String> updated = new LinkedHashSet<>();
        private Set<String> deleted = new LinkedHashSet<>();

        void toMany() {
            many = true;
            // 换成空集合而不是 clear：上万条 ID 立刻可回收，之后的登记是 O(1) 的忽略。
            created = Set.of();
            updated = Set.of();
            deleted = Set.of();
        }
    }
}

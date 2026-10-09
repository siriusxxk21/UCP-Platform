package com.lingan.ucp.nocode.web.live;

import com.lingan.ucp.nocode.runtime.service.live.RecordChangeBatch;
import com.lingan.ucp.nocode.runtime.service.live.RecordChangeCollector;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 一个订阅的待发状态：一个分发周期内到达的变更先并到这里，周期结束时转成一帧。只在分发线程上访问。
 *
 * <p>合并规则与登记簿相同（新增后修改仍是新增、新增后删除净效果为无、修改后删除是删除），记录数超过上限即不再逐条列出。
 * 交付不进去时保留并标记「落后」：之后成功交付的那一帧只说「整体刷新」，不带任何记录标识。
 */
public final class RecordLivePending {
    private final boolean idsVisible;
    private boolean present;
    private boolean object;
    private boolean many;
    private boolean behind;
    private final Set<String> created = new LinkedHashSet<>();
    private final Set<String> updated = new LinkedHashSet<>();
    private final Set<String> deleted = new LinkedHashSet<>();
    private long fromSeq;
    private long seq;
    private boolean originSeen;
    private boolean originMixed;
    private String origin;

    public RecordLivePending(boolean idsVisible) {
        this.idsVisible = idsVisible;
    }

    /** 并入一份变更。sequence 是该对象在本进程内的通知序号，from 是这份变更的发起人标识（可为空）。 */
    public void merge(RecordChangeBatch.ObjectChange change, long sequence, String from) {
        if (!present) {
            present = true;
            fromSeq = sequence;
        }
        seq = sequence;
        if (!originSeen) {
            originSeen = true;
            origin = from;
        } else if (!Objects.equals(origin, from)) {
            originMixed = true;
        }
        if (change.many()) {
            many = true;
            dropIds();
            return;
        }
        if (object) return;
        for (String id : change.created()) {
            if (deleted.remove(id)) updated.add(id);
            else if (!updated.contains(id)) created.add(id);
        }
        for (String id : change.updated()) {
            if (deleted.remove(id)) updated.add(id);
            else if (!created.contains(id)) updated.add(id);
        }
        for (String id : change.deleted()) {
            if (created.remove(id)) continue;
            updated.remove(id);
            deleted.add(id);
        }
        if (created.size() + updated.size() + deleted.size() > RecordChangeCollector.ID_CAP) {
            many = true;
            dropIds();
        }
    }

    /** 这个订阅漏掉了变更（交付不进去，或分发队列溢出）：下一帧只说「整体刷新」。 */
    public void fallBehind() {
        present = true;
        behind = true;
        dropIds();
    }

    /**
     * 当前待发状态对应的一帧；没有可发的内容时返回 null。current 是该对象此刻的序号，只用于落后补发的那一帧。
     *
     * <p>不改变状态：交付成功后调用 {@link #delivered()}，交付不进去调用 {@link #fallBehind()}。
     */
    public RecordsChanged frame(String objectId, String epoch, long current) {
        if (!present) return null;
        if (behind)
            return new RecordsChanged(
                    objectId,
                    RecordsChanged.KIND_OBJECT,
                    false,
                    List.of(),
                    List.of(),
                    List.of(),
                    null,
                    epoch,
                    current,
                    current);
        String single = originMixed ? null : origin;
        if (object)
            return new RecordsChanged(
                    objectId,
                    RecordsChanged.KIND_OBJECT,
                    many,
                    List.of(),
                    List.of(),
                    List.of(),
                    single,
                    epoch,
                    fromSeq,
                    seq);
        // 窗口内新增后又删除：净效果为无，先不发；已占用的序号留给下一帧一并覆盖，序号仍然接续。
        if (created.isEmpty() && updated.isEmpty() && deleted.isEmpty()) return null;
        if (!idsVisible)
            return new RecordsChanged(
                    objectId,
                    RecordsChanged.KIND_OBJECT,
                    false,
                    List.of(),
                    List.of(),
                    List.of(),
                    single,
                    epoch,
                    fromSeq,
                    seq);
        return new RecordsChanged(
                objectId,
                RecordsChanged.KIND_IDS,
                false,
                List.copyOf(created),
                List.copyOf(updated),
                List.copyOf(deleted),
                single,
                epoch,
                fromSeq,
                seq);
    }

    /** 这一帧已交给订阅的事件流：清空，下一份变更从新的序号范围开始。 */
    public void delivered() {
        present = false;
        object = false;
        many = false;
        behind = false;
        created.clear();
        updated.clear();
        deleted.clear();
        originSeen = false;
        originMixed = false;
        origin = null;
    }

    private void dropIds() {
        object = true;
        created.clear();
        updated.clear();
        deleted.clear();
    }
}

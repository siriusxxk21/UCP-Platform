package com.lingan.ucp.nocode.metadata.service.object;

import static com.lingan.ucp.nocode.api.NocodeErrorCodes.invalid;

import com.lingan.ucp.nocode.api.*;
import com.lingan.ucp.nocode.api.DataCenter.*;
import com.lingan.ucp.nocode.enums.*;
import com.lingan.ucp.nocode.metadata.dal.mapper.DataCenterMapper;
import com.lingan.ucp.nocode.metadata.dal.mapper.ObjectDraftMapper;
import com.lingan.ucp.nocode.metadata.service.request.ReadRequestMemo;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;

/** 依赖写入与对象变更使用同一事务级锁，避免引用登记和字段停用相互穿透。 */
@Service
public class DataObjectApiImpl implements DataObjectApi {
    @Resource private PlatformTransactionManager manager;

    @Resource private ObjectDesignService designs;
    @Resource private DataCenterMapper store;
    @Resource private ObjectDraftMapper objects;
    private TransactionTemplate transaction;

    /** 初始化模块专用的定义读取器，注册时间等类型支持；不改变底座共享 JSON 配置。 */
    @PostConstruct
    void initialize() {
        this.transaction = new TransactionTemplate(manager);
    }

    @Override
    public Definition getPublished(String objectId) {
        return getVersion(objectId, null).definition();
    }

    /** 一次读取的结果；current 表示读到的版本就是对象头上的当前发布版本。 */
    private record VersionRead(PublishedObject object, boolean current) {}

    /**
     * 只读作用域内同一事务（或同一请求的无事务段）对同一对象版本只读取、解析一次；作用域外逐次读取。
     *
     * <p>「指定版本」与「当前发布版本」指向同一版本时共用一份结果：事务内对象头已被共享锁锁住，发布指针不会变化； 无事务时按请求内首次读取为准。
     */
    @Override
    public PublishedObject getVersion(String objectId, Integer versionNo) {
        VersionRead read =
                ReadRequestMemo.once(
                        ReadRequestMemo.key("object.version", objectId, versionNo),
                        () -> readVersion(objectId, versionNo));
        if (read.current())
            ReadRequestMemo.seed(
                    ReadRequestMemo.key(
                            "object.version",
                            objectId,
                            versionNo == null ? read.object().versionNo() : null),
                    read);
        return read.object();
    }

    private VersionRead readVersion(String objectId, Integer versionNo) {
        return transaction.execute(
                status -> {
                    var h = designs.head(objectId, false);
                    if (!ObjectStatusEnum.ACTIVE.matches(h.getStatus())
                            || h.getCurrentPublishedVersionNo() == null)
                        throw invalid("对象尚未发布或已停用");
                    int selected = versionNo == null ? h.getCurrentPublishedVersionNo() : versionNo;
                    var version =
                            store.versions(h.getId()).stream()
                                    .filter(
                                            v ->
                                                    v.versionNo() == selected
                                                            && VersionStateEnum.PUBLISHED.matches(
                                                                    v.state()))
                                    .findFirst()
                                    .orElseThrow(() -> invalid("指定对象版本不存在或尚未发布"));
                    var definition =
                            ObjectTables.normalize(
                                    designs.read(
                                            store.versionSchema(h.getId(), selected),
                                            Definition.class));
                    return new VersionRead(
                            new PublishedObject(objectId, selected, version.checksum(), definition),
                            selected == h.getCurrentPublishedVersionNo());
                });
    }

    @Override
    public void registerDependency(Dependency dependency, long actorId) {
        if (dependency == null || actorId <= 0) throw invalid("依赖登记需要受信操作者和资源信息");
        validateSource(dependency.sourceKind(), dependency.sourceKey());
        if (dependency.sourceName() == null
                || dependency.sourceName().isBlank()
                || dependency.sourceName().length() > 160) throw invalid("依赖资源名称无效");
        transaction.executeWithoutResult(
                status -> {
                    objects.lockTableName("nocode-design-write");
                    var definition = getPublished(dependency.targetObjectId());
                    Set<String> available = new HashSet<>();
                    definition.fields().forEach(f -> available.add(f.id()));
                    definition.details().stream()
                            .filter(t -> MemberStateEnum.ACTIVE.matches(t.state()))
                            .forEach(t -> t.fields().forEach(f -> available.add(f.id())));
                    var fields =
                            dependency.fieldIds() == null
                                    ? List.<String>of()
                                    : dependency.fieldIds();
                    if (fields.size() > 4000
                            || new HashSet<>(fields).size() != fields.size()
                            || !available.containsAll(fields)) throw invalid("依赖字段不属于对象的已发布版本");
                    store.upsertDependency(
                            dependency, designs.write(fields), Long.toString(actorId));
                    designs.audit(
                            Long.parseLong(definition.objectId()),
                            actorId,
                            AuditOperationEnum.OBJECT_DEPENDENCY_REGISTER.getCode(),
                            dependency);
                });
    }

    @Override
    public void removeDependencies(String kind, String key, long actorId) {
        if (actorId <= 0) throw invalid("依赖移除需要受信操作者");
        validateSource(kind, key);
        transaction.executeWithoutResult(
                status -> {
                    objects.lockTableName("nocode-design-write");
                    store.deleteDependencies(kind, key, Long.toString(actorId));
                });
    }

    private void validateSource(String kind, String key) {
        if (!DependencyKindEnum.containsCode(Objects.toString(kind, ""))
                || key == null
                || key.isBlank()
                || key.length() > 160) throw invalid("依赖资源类型或标识无效");
    }
}

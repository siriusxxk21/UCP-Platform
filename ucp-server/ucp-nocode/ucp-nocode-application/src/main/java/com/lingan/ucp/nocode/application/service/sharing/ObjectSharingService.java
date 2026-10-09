package com.lingan.ucp.nocode.application.service.sharing;

import static com.lingan.ucp.nocode.api.NocodeErrorCodes.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lingan.ucp.framework.common.biz.system.permission.PermissionCommonApi;
import com.lingan.ucp.framework.common.exception.ServiceException;
import com.lingan.ucp.nocode.api.*;
import com.lingan.ucp.nocode.api.ApplicationAuthorization.ObjectGrant;
import com.lingan.ucp.nocode.application.dal.dataobject.*;
import com.lingan.ucp.nocode.application.dal.mapper.*;
import com.lingan.ucp.nocode.enums.*;
import com.lingan.ucp.nocode.metadata.dal.mapper.ObjectDraftMapper;
import com.lingan.ucp.nocode.metadata.service.object.DraftValidator;
import com.lingan.ucp.nocode.metadata.service.request.ReadRequestMemo;

import jakarta.annotation.Resource;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;

/** 数据管理员决定对象对应用的授权上限。应用创建人和超级管理员运行时都不能跳过上限。 */
@Service
public class ObjectSharingService {
    public static final String MANAGE_PERMISSION = "nocode:object:share";
    @Resource private ObjectApplicationGrantMapper store;
    @Resource private ApplicationMapper applications;
    @Resource private ObjectDraftMapper designLocks;
    @Resource private DataObjectApi objects;
    @Resource private DraftValidator validator;
    @Resource private ObjectGrantValidator grants;
    @Resource private ImpliedObjects impliedObjects;
    @Resource private PermissionCommonApi permissions;
    @Resource private ObjectMapper json;
    @Resource private PlatformTransactionManager manager;

    public void requireManager(long actor) {
        if (actor <= 0 || !permissions.hasAnyPermissions(actor, MANAGE_PERMISSION))
            throw new AccessDeniedException("没有对象共享授权管理权限");
    }

    public List<ObjectSharing.Target> targets(long actor) {
        requireManager(actor);
        return store.targets();
    }

    public List<ObjectSharing.Grant> forObject(String object, long actor) {
        requireManager(actor);
        return store.forObject(validator.id(object, "对象")).stream().map(this::view).toList();
    }

    /** 调用方负责应用设计权限；仅显示该应用的上限，不能在应用侧更改。 */
    public List<ObjectSharing.Grant> forApplication(String app) {
        return store.forApplication(validator.id(app, "应用")).stream().map(this::view).toList();
    }

    /**
     * 库里存的原样授权：清单可能是「全部」哨兵，也可能带已停用的 ID。只能交给 {@link ObjectGrantValidator} 的 normalize / within /
     * intersect / resolve 或 {@link SystemReadAccess}，不能直接拿清单做包含判断。
     *
     * <p>只读作用域内同一事务（或同一请求的无事务段）对同一对象、应用的授权上限只读取一次。
     */
    public ObjectGrant storedPermission(String object, String app) {
        return ReadRequestMemo.once(
                ReadRequestMemo.key("sharing.permission", object, app),
                () -> decode(store.find(validator.id(object, "对象"), validator.id(app, "应用"))));
    }

    /**
     * 应用对一个对象的「有效上限」，运行期口径：有授权行按授权行（数据管理员显式配的永远优先；已撤销 ⇒ 无，这就是单独关掉的入口）； 没有授权行、但对象是本应用因关联而隐式可读的 ⇒
     * 不落库的只读全量上限；否则无。返回的仍是存储形态，清单可能是「全部」。
     */
    public ObjectGrant ceiling(String applicationId, DataCenter.Definition definition) {
        // 只读作用域内同一事务对同一对象、应用只算一次：结果只取决于授权行、应用的已发布版本和对象是否已发布，
        // 这三样在持着目录共享锁的只读事务里不变（与 storedPermission、应用已发布版本的记忆同一前提）。
        return ReadRequestMemo.once(
                ReadRequestMemo.key("sharing.ceiling", definition.objectId(), applicationId),
                () ->
                        ceiling(
                                applicationId,
                                definition,
                                () ->
                                        impliedObjects.implied(
                                                applicationId, definition.objectId())));
    }

    /** 同上；对象是不是隐式可读由调用方给出（设计预览按草稿引用判断，运行期按已发布版本判断），只在没有授权行时才会去问。 */
    public ObjectGrant ceiling(
            String applicationId,
            DataCenter.Definition definition,
            java.util.function.BooleanSupplier implied) {
        // 有效的授权行（绝大多数情况）直接复用 storedPermission 的读取与请求内记忆，同一请求里这一行不读第二次。
        ObjectGrant stored = storedPermission(definition.objectId(), applicationId);
        if (stored != null) return stored;
        // 读不到有效授权时才再看一眼：分清「有行但已撤销」与「没有行」——前者是数据管理员单独关掉的，不走隐式。
        var row =
                store.find(
                        validator.id(definition.objectId(), "对象"),
                        validator.id(applicationId, "应用"));
        if (row != null) return decode(row);
        return implied.getAsBoolean() ? impliedGrant(definition.objectId()) : null;
    }

    /** 隐式只读的虚拟上限：只有查看、全部记录、可查看字段 / 明细 / 关系为「全部」，没有任何写，没有记录条件。 */
    public static ObjectGrant impliedGrant(String objectId) {
        Set<String> all = Set.of(Selections.ALL);
        return new ObjectGrant(
                objectId,
                Set.of(ApplicationActionEnum.READ.getCode()),
                ApplicationScopeEnum.ALL.getCode(),
                all,
                Set.of(),
                all,
                Set.of(),
                all,
                Set.of(),
                Map.of(),
                Set.of());
    }

    public void requireReference(String object, String app) {
        var row = store.find(validator.id(object, "对象"), validator.id(app, "应用"));
        if (decode(row) != null) return;
        // 调用方已验证对象引用和设计权限；错误面向搭建者，使用业务名称而非内部编号。
        var definition = objects.getVersion(object, null).definition();
        var application = applications.selectById(validator.id(app, "应用"));
        String appName = application == null ? "当前应用" : application.getAppName();
        throw invalid(
                "数据对象“"
                        + definition.objectName()
                        + "”"
                        + (row == null ? "尚未授权给应用“" : "授予应用“")
                        + appName
                        + (row == null ? "”。" : "”的共享授权已被撤销。")
                        + "\n配置位置：应用中心 → 当前应用 → 已引用对象 → “"
                        + definition.objectName()
                        + "”对象行 → 配置数据权限。"
                        + "\n如何修正："
                        + (row == null ? "新增应用授权" : "重新授权")
                        + "，为当前应用“"
                        + appName
                        + "”，配置允许的操作、记录范围和可查看字段，填写变更说明并保存。"
                        + "没有共享授权管理权限时，请联系数据管理员处理。");
    }

    public ObjectSharing.Grant save(ObjectSharing.Save request, long actor) {
        requireManager(actor);
        if (request == null) throw invalid("缺少共享授权配置");
        long object = validator.id(request.objectId(), "对象"),
                app = validator.id(request.applicationId(), "应用");
        String reason = validator.text(request.reason(), "授权变更说明", 1000);
        return new TransactionTemplate(manager)
                .execute(
                        tx -> {
                            // 与设计发布保持锁顺序。运行事务已有应用头共享锁，撤权会等待已开始的请求完成。
                            designLocks.lockTableName("nocode-design-write");
                            if (applications.lock(app, true) == null) throw invalid("应用不存在");
                            var previous = store.find(object, app);
                            if ((previous == null ? 0 : previous.getLockVersion())
                                    != request.expectedRevision())
                                throw new ServiceException(CONFLICT, "共享授权已被修改，请刷新后重试");
                            // 落库的是规范化后的授权：死 ID 去掉、计算取数置空、「全部」原样保留。
                            ObjectGrant permission = request.permission();
                            if (permission != null) {
                                DataCenter.Definition latest =
                                        objects.getVersion(request.objectId(), null).definition();
                                permission =
                                        grants.normalize(
                                                permission,
                                                latest,
                                                ObjectGrantValidator.Universe.of(latest));
                            }
                            // 停用对象也必须能够撤权，不经仅允许读取已启用版本的 API。
                            else if (designLocks.selectById(object) == null) throw invalid("对象不存在");
                            try {
                                store.save(
                                        object,
                                        app,
                                        json.writeValueAsString(permission),
                                        reason,
                                        Long.toString(actor));
                                store.audit(object, app, Long.toString(actor));
                                return view(store.find(object, app));
                            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                                throw invalid("共享授权无法保存");
                            }
                        });
    }

    /** 首次引用对象时的默认上限：六类业务操作（不含发起流程）、全部记录；字段、明细、多对多关系的读写六个清单都是「全部」，随对象版本自动同步。 */
    public ObjectGrant defaultGrant(String objectId, DataCenter.Definition d) {
        Set<String> all = Set.of(Selections.ALL);
        var actions =
                Set.of(
                        ApplicationActionEnum.READ.getCode(),
                        ApplicationActionEnum.CREATE.getCode(),
                        ApplicationActionEnum.UPDATE.getCode(),
                        ApplicationActionEnum.DELETE.getCode(),
                        ApplicationActionEnum.IMPORT.getCode(),
                        ApplicationActionEnum.EXPORT.getCode());
        return new ObjectGrant(
                objectId,
                actions,
                ApplicationScopeEnum.ALL.getCode(),
                all,
                all,
                all,
                all,
                all,
                all,
                Map.of(),
                Set.of());
    }

    /**
     * 引用即授权，只写一次：这对（对象，应用）还没有任何授权行时写入默认上限；已有行——不管有效还是已撤销——一律不动， 数据管理员收窄或撤销过的授权不会因为应用同步对象版本而被重新放开。
     * 绕过共享管理权限校验，必须在调用方已持有设计写锁与应用头锁的事务内调用，不另开事务或加锁。
     */
    public void applyDefaultGrant(
            String objectId, String applicationId, DataCenter.Definition definition, long actor) {
        long object = validator.id(objectId, "对象"), app = validator.id(applicationId, "应用");
        if (store.find(object, app) != null) return;
        var grant = defaultGrant(objectId, definition);
        grants.validate(grant, definition);
        try {
            store.save(
                    object, app, json.writeValueAsString(grant), "引用对象默认授权", Long.toString(actor));
            store.audit(object, app, Long.toString(actor));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw invalid("默认共享授权无法保存");
        }
    }

    private ObjectGrant decode(NocodeObjectApplicationGrantDO data) {
        if (data == null) return null;
        try {
            return json.readValue(data.getGrantJson(), ObjectGrant.class);
        } catch (java.io.IOException e) {
            throw invalid("对象共享授权无法读取，已拒绝访问");
        }
    }

    private ObjectSharing.Grant view(NocodeObjectApplicationGrantDO row) {
        var app = applications.selectById(row.getApplicationId());
        return new ObjectSharing.Grant(
                row.getObjectId().toString(),
                row.getApplicationId().toString(),
                app == null ? "应用已删除" : app.getAppName(),
                row.getLockVersion(),
                decode(row),
                row.getReason(),
                row.getUpdater(),
                row.getUpdateTime());
    }
}

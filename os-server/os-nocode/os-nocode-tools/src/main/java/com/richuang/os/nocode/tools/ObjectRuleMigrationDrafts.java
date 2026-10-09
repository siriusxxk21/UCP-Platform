package com.richuang.os.nocode.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.richuang.os.nocode.api.DataCenter;
import com.richuang.os.nocode.api.ObjectTables;
import com.richuang.os.nocode.enums.VersionStateEnum;
import com.richuang.os.nocode.metadata.service.object.ObjectDesignService;

import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 对象上未发布草稿与当前已发布版本是否语义一致（设计稿 9.2「空草稿」）。
 *
 * <p>不另拼比较字段：两边都用系统自己的读取与编码。草稿侧是 {@link ObjectDesignService#definition}（发布计划做 snapshot
 * 时读的就是它，含对象头、标题模板等对象级设置、字段及其全部扩展配置、关联、索引、明细、主表绑定），已发布侧是 {@link
 * ObjectDesignService#published}（发布编译比对的基线）；两者先经 {@link ObjectTables#normalize}（兼容旧快照缺少的表级绑定等），
 * 再经对象设计的 JSON 编码转成树比较——对象键顺序无关、数组顺序有关。任何一处不同都算「有改动」，保守地仍然阻断。
 *
 * <p>判断在单独的事务里做、且标记只回滚：对象设计的读取对对象头加共享锁（{@code FOR SHARE}），PostgreSQL 不允许在只读事务里执行， 所以不能并进 dry-run
 * 的只读事务；只回滚保证判断过程不留下任何写入，共享锁也让判断期间别人保存不了这份草稿。
 */
final class ObjectRuleMigrationDrafts {
    private ObjectRuleMigrationDrafts() {}

    /** draftVersion 为草稿版本号，publishedVersion 为当前发布版本号；identical 为 true 即空草稿。 */
    record Verdict(int draftVersion, Integer publishedVersion, boolean identical) {}

    /** 对象当前没有未发布草稿时返回 null。 */
    static Verdict judge(
            ObjectDesignService designs, PlatformTransactionManager transactions, String objectId) {
        var tx = new TransactionTemplate(transactions);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setTimeout(300);
        return tx.execute(
                status -> {
                    status.setRollbackOnly();
                    return compare(designs, objectId);
                });
    }

    private static Verdict compare(ObjectDesignService designs, String objectId) {
        var head = designs.head(objectId, false);
        if (!VersionStateEnum.DRAFT.matches(head.getVersionState())) return null;
        var published = designs.published(objectId);
        boolean identical =
                published != null
                        && tree(designs, designs.definition(objectId))
                                .equals(tree(designs, published));
        return new Verdict(
                head.getLatestVersionNo(), head.getCurrentPublishedVersionNo(), identical);
    }

    private static JsonNode tree(ObjectDesignService designs, DataCenter.Definition definition) {
        return designs.read(designs.write(ObjectTables.normalize(definition)), JsonNode.class);
    }
}

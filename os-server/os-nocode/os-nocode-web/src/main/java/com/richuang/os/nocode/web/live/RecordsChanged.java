package com.richuang.os.nocode.web.live;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * records.changed 事件的 data。只有对象、记录标识与序号，一定不带字段值、操作人、应用标识、明细行标识与表名。
 *
 * @param objectId 等于主题里的对象标识
 * @param kind ids 表示逐条列出；object 表示不知道具体哪些记录，三个列表都为空
 * @param many 仅当因为量大而不再逐条列出时为 true；因权限不给标识、因落后补发时为 false
 * @param created 新增的主记录标识
 * @param updated 修改的主记录标识
 * @param deleted 删除的主记录标识
 * @param origin 本帧合并的所有变更都来自同一个发起人标识时取该值，否则为 null；键总是输出
 * @param epoch 服务进程启动时生成的随机串
 * @param fromSeq 本帧覆盖的第一个序号
 * @param seq 本帧覆盖的最后一个序号
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record RecordsChanged(
        String objectId,
        String kind,
        boolean many,
        List<String> created,
        List<String> updated,
        List<String> deleted,
        String origin,
        String epoch,
        long fromSeq,
        long seq) {
    public static final String KIND_IDS = "ids";
    public static final String KIND_OBJECT = "object";
}

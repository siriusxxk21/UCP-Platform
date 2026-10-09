package com.lingan.ucp.nocode.api;

import java.math.BigDecimal;
import java.util.List;

/**
 * 设计引擎区块与外部设计引擎之间的契约（laneEG）。
 *
 * <p>系统给区块签发短期令牌（HS256，声明见 {@code EngineTokens}）；引擎只能凭令牌调用「取库（只读）」与「材料清单写回」两个接口，
 * 令牌只代表「谁、哪条记录、可读/可写」，实际数据权限每次按令牌用户实时判定。
 */
public final class EngineLink {
    private EngineLink() {}

    /** 区块打开时请求令牌：只给已发布页面、节点与记录标识，对象由页面当前对象决定。 */
    public record Issue(String applicationId, String pageId, String nodeId, String recordId) {}

    /**
     * @param token 交给引擎的令牌（只经 postMessage 传递，不进 URL）
     * @param expiresAt 过期时间（epoch 秒）
     * @param engineUrl iframe 加载的站内路径
     * @param writable 当前用户对该记录是否有编辑权（UPDATE）
     * @param project 引擎项目键：应用/对象/记录
     */
    public record Issued(
            String token, long expiresAt, String engineUrl, boolean writable, String project) {}

    /** 库项（按区块字段映射归一）。 */
    public record LibraryItem(
            String recordId,
            String objectId,
            String name,
            String manufacturer,
            String model,
            String specification,
            String unit,
            Object unitPrice,
            String methodCode) {}

    public record LibraryPage(List<LibraryItem> list, long total) {}

    /** 引擎行引用的库记录。 */
    public record LibraryRef(String objectId, String recordId) {}

    /** 引擎工程量行（Engine01 quantity_takeoff 的输出；引擎发送前转成 camelCase）。 */
    public record BomRow(
            String key,
            String objectKind,
            String materialId,
            LibraryRef libraryRef,
            String name,
            String methodCode,
            List<String> spaceIds,
            BigDecimal quantity,
            String unit,
            BigDecimal unitPrice,
            String status,
            String basis,
            List<String> engineObjectIds) {}

    public record BomCommand(List<BomRow> rows) {}

    public record BomError(String key, String message) {}

    public record BomResult(
            int created, int updated, int deleted, int unchanged, List<BomError> errors) {}
}

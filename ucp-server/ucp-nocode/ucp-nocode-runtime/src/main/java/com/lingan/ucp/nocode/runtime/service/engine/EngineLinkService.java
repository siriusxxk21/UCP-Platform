package com.lingan.ucp.nocode.runtime.service.engine;

import static com.lingan.ucp.nocode.api.NocodeErrorCodes.invalid;

import com.lingan.ucp.framework.common.exception.ServiceException;
import com.lingan.ucp.framework.common.pojo.PageResult;
import com.lingan.ucp.module.system.api.user.AdminUserApi;
import com.lingan.ucp.module.system.api.user.dto.AdminUserRespDTO;
import com.lingan.ucp.nocode.api.ApplicationCenter;
import com.lingan.ucp.nocode.api.ApplicationRecords;
import com.lingan.ucp.nocode.api.ApplicationUi;
import com.lingan.ucp.nocode.api.EngineLink;
import com.lingan.ucp.nocode.api.FieldDefinition;
import com.lingan.ucp.nocode.application.service.application.ApplicationService;
import com.lingan.ucp.nocode.application.service.resource.ApplicationPageBindings;
import com.lingan.ucp.nocode.application.service.resource.ApplicationResourceValidator;
import com.lingan.ucp.nocode.enums.ApplicationActionEnum;
import com.lingan.ucp.nocode.enums.ApplicationNodeKindEnum;
import com.lingan.ucp.nocode.enums.ApplicationResourceKindEnum;
import com.lingan.ucp.nocode.runtime.service.record.RecordService;

import jakarta.annotation.Resource;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 设计引擎区块的系统侧（laneEG，DESIGN.md §1.3、§2.2、§3.2）。
 *
 * <p>权限来源只有一个：令牌用户在应用里的现有授权。签发时按 {@link RecordService#get} 判读权、按记录能力里的 UPDATE 定可写（只收不放）；
 * 引擎每次取库或写回都重新验签、回查已发布页面节点、实时复查该用户对记录的读/写权，再以该用户身份走 {@link RecordService} 的正常读写链路
 * （字段授权、联动、编号、历史照常）。令牌本身不授予任何权限。
 */
@Service
public class EngineLinkService {
    static final int MAX_ROWS = 500;
    private static final int PAGE_SIZE = 100;
    private static final int MAX_PAGES = 50;
    private static final int LIBRARY_PAGE_SIZE = 50;

    @Resource private EngineTokens tokens;
    @Resource private ApplicationService applications;
    @Resource private ApplicationResourceValidator resourceValidator;
    @Resource private ApplicationPageBindings pageBindings;
    @Resource private RecordService records;
    @Resource private AdminUserApi users;

    @Value("${os.nocode.engine.default-url:/engine01/}")
    private String defaultUrl;

    private record Bound(ApplicationUi.Page page, ApplicationUi.EngineBlock engine) {}

    private record Linked(EngineTokens.Claims claims, ApplicationUi.EngineBlock engine) {}

    /** 区块打开时签发：只认已发布页面里的 ENGINE 节点，对象 = 页面当前对象。 */
    public EngineLink.Issued issue(EngineLink.Issue query, long actor) {
        if (query == null
                || blank(query.applicationId())
                || blank(query.pageId())
                || blank(query.nodeId())
                || blank(query.recordId())) throw invalid("请先选择一条记录");
        Bound bound = bound(query.applicationId(), query.pageId(), query.nodeId());
        String object = bound.page().contextObjectId();
        ApplicationRecords.Aggregate current =
                records.get(query.applicationId(), object, query.recordId(), actor);
        boolean writable = canUpdate(current);
        AdminUserRespDTO user = users.getUser(actor);
        long now = tokens.now(), exp = now + tokens.ttl();
        String token =
                tokens.sign(
                        new EngineTokens.Claims(
                                query.applicationId(),
                                object,
                                current.record().id(),
                                query.pageId(),
                                query.nodeId(),
                                actor,
                                user == null ? "" : user.getNickname(),
                                writable,
                                now,
                                exp,
                                tokens.newTokenId()));
        String url = bound.engine().engineUrl();
        return new EngineLink.Issued(
                token,
                exp,
                blank(url) ? defaultUrl : url,
                writable,
                query.applicationId() + "/" + object + "/" + current.record().id());
    }

    /** 引擎取库（只读）：材料库或构件库，按区块字段映射归一。 */
    public EngineLink.LibraryPage library(String token, String kind, String search, int pageNo) {
        Linked linked = link(token, false);
        ApplicationUi.EngineLibrary library =
                "COMPONENT".equals(kind)
                        ? linked.engine().components()
                        : "MATERIAL".equals(kind) ? linked.engine().materials() : null;
        if (!"COMPONENT".equals(kind) && !"MATERIAL".equals(kind)) throw invalid("库类型无效");
        if (library == null || blank(library.objectId()))
            throw invalid("设计引擎区块未配置" + ("MATERIAL".equals(kind) ? "材料库" : "构件库"));
        String text = search == null ? null : search.strip();
        if (text != null && text.length() > 100) text = text.substring(0, 100);
        EngineTokens.Claims claims = linked.claims();
        PageResult<ApplicationRecords.Row> page =
                records.page(
                        new ApplicationRecords.Query(
                                claims.app(),
                                library.objectId(),
                                Math.max(1, Math.min(1000, pageNo)),
                                LIBRARY_PAGE_SIZE,
                                blank(text) ? null : text,
                                null,
                                null,
                                false),
                        claims.uid());
        Map<String, String> fields = library.fields() == null ? Map.of() : library.fields();
        List<EngineLink.LibraryItem> items = new ArrayList<>();
        for (ApplicationRecords.Row row : page.getList())
            items.add(
                    new EngineLink.LibraryItem(
                            row.id(),
                            library.objectId(),
                            text(row, fields.get("name")),
                            text(row, fields.get("manufacturer")),
                            text(row, fields.get("model")),
                            text(row, fields.get("specification")),
                            text(row, fields.get("unit")),
                            raw(row, fields.get("unitPrice")),
                            text(row, fields.get("methodCode"))));
        return new EngineLink.LibraryPage(
                items, page.getTotal() == null ? items.size() : page.getTotal());
    }

    /** 材料清单写回（幂等）：按「关联字段 = 当前记录」+「行键」定位；有则更新、无则新建；引擎以前写过（行键非空）而本次没有的删除；行键为空的人工行不动。 */
    public EngineLink.BomResult bom(String token, EngineLink.BomCommand command) {
        Linked linked = link(token, true);
        ApplicationUi.EngineWriteback target = linked.engine().bom();
        if (target == null || blank(target.objectId())) throw invalid("设计引擎区块未配置材料清单写回");
        List<EngineLink.BomRow> rows =
                command == null || command.rows() == null ? List.of() : command.rows();
        if (rows.size() > MAX_ROWS) throw invalid("一次最多写回 " + MAX_ROWS + " 行");
        Set<String> keys = new HashSet<>();
        for (EngineLink.BomRow row : rows)
            if (row == null || blank(row.key()) || row.key().length() > 200 || !keys.add(row.key()))
                throw invalid("写回行的行键为空、过长或重复");
        EngineTokens.Claims claims = linked.claims();
        Map<String, String> mapping = target.fields() == null ? Map.of() : target.fields();
        Map<String, FieldDefinition> fields = fieldsOf(claims, target.objectId());
        Map<String, ApplicationRecords.Row> existing = existing(claims, target);
        ApplicationUi.EngineLibrary materials = linked.engine().materials();
        int created = 0, updated = 0, deleted = 0, unchanged = 0;
        List<EngineLink.BomError> errors = new ArrayList<>();
        for (EngineLink.BomRow row : rows) {
            try {
                Map<String, Object> values =
                        desired(claims, target, mapping, fields, materials, row);
                ApplicationRecords.Row current = existing.get(row.key());
                if (current != null && same(current, values)) {
                    unchanged++;
                    continue;
                }
                records.save(
                        new ApplicationRecords.Save(
                                claims.app(),
                                target.objectId(),
                                current == null ? null : current.id(),
                                current == null ? null : current.revision(),
                                values,
                                null),
                        claims.uid());
                if (current == null) created++;
                else updated++;
            } catch (ServiceException e) {
                errors.add(new EngineLink.BomError(row.key(), e.getMessage()));
            }
        }
        for (Map.Entry<String, ApplicationRecords.Row> entry : existing.entrySet()) {
            if (keys.contains(entry.getKey())) continue;
            try {
                records.delete(
                        new ApplicationRecords.Delete(
                                claims.app(),
                                target.objectId(),
                                entry.getValue().id(),
                                entry.getValue().revision()),
                        claims.uid());
                deleted++;
            } catch (ServiceException e) {
                errors.add(new EngineLink.BomError(entry.getKey(), e.getMessage()));
            }
        }
        return new EngineLink.BomResult(created, updated, deleted, unchanged, errors);
    }

    // ---- 令牌 → 当前用户、记录、区块配置（每次实时复查） -----------------------------------------
    private Linked link(String token, boolean write) {
        EngineTokens.Claims claims = tokens.verify(token);
        if (write && !claims.writable()) throw forbidden("只读：没有编辑此记录的权限");
        AdminUserRespDTO user = users.getUser(claims.uid());
        if (user == null || !Integer.valueOf(0).equals(user.getStatus()))
            throw EngineTokens.denied("设计引擎令牌的用户不存在或已停用");
        Bound bound = bound(claims.app(), claims.page(), claims.node());
        if (!Objects.equals(bound.page().contextObjectId(), claims.obj()))
            throw EngineTokens.denied("设计引擎令牌与页面配置不符");
        ApplicationRecords.Aggregate current =
                records.get(claims.app(), claims.obj(), claims.rec(), claims.uid());
        if (write && !canUpdate(current)) throw forbidden("只读：没有编辑此记录的权限");
        return new Linked(claims, bound.engine());
    }

    private Bound bound(String app, String pageId, String nodeId) {
        ApplicationCenter.Resource resource =
                applications.published(app).definition().resources().stream()
                        .filter(
                                r ->
                                        Objects.equals(r.id(), pageId)
                                                && ApplicationResourceKindEnum.PAGE.matches(
                                                        r.kind()))
                        .findFirst()
                        .orElseThrow(() -> invalid("页面未发布或不存在"));
        ApplicationUi.Page page =
                resourceValidator.decode(resource.config(), ApplicationUi.Page.class);
        ApplicationUi.Node node = pageBindings.node(page.nodes(), nodeId);
        if (node == null || !ApplicationNodeKindEnum.ENGINE.matches(node.type()))
            throw invalid("设计引擎区块不存在");
        if (blank(page.contextObjectId())) throw invalid("设计引擎区块只能放在有当前记录对象的页面");
        ApplicationUi.EngineBlock engine =
                node.engine() == null
                        ? new ApplicationUi.EngineBlock(null, null, null, null)
                        : node.engine();
        return new Bound(page, engine);
    }

    private static boolean canUpdate(ApplicationRecords.Aggregate aggregate) {
        return aggregate != null
                && aggregate.record() != null
                && aggregate.record().permissions() != null
                && aggregate.record().permissions().actions() != null
                && aggregate
                        .record()
                        .permissions()
                        .actions()
                        .contains(ApplicationActionEnum.UPDATE.getCode());
    }

    // ---- 写回细节 -------------------------------------------------------------------------------
    private Map<String, FieldDefinition> fieldsOf(EngineTokens.Claims claims, String object) {
        Map<String, FieldDefinition> result = new HashMap<>();
        for (FieldDefinition field :
                records.model(claims.app(), object, claims.uid()).object().fields())
            result.put(field.id(), field);
        return result;
    }

    private Map<String, ApplicationRecords.Row> existing(
            EngineTokens.Claims claims, ApplicationUi.EngineWriteback target) {
        Map<String, ApplicationRecords.Row> result = new LinkedHashMap<>();
        for (int page = 1; page <= MAX_PAGES; page++) {
            PageResult<ApplicationRecords.Row> rows =
                    records.page(
                            new ApplicationRecords.Query(
                                    claims.app(),
                                    target.objectId(),
                                    page,
                                    PAGE_SIZE,
                                    null,
                                    Map.of(target.recordFieldId(), claims.rec()),
                                    null,
                                    false),
                            claims.uid());
            for (ApplicationRecords.Row row : rows.getList()) {
                // 双保险：查询条件之外再核一次归属，别的记录的行永不触及。
                if (!Objects.equals(string(row.values().get(target.recordFieldId())), claims.rec()))
                    continue;
                String key = string(row.values().get(target.keyFieldId()));
                if (!blank(key)) result.putIfAbsent(key, row);
            }
            if (rows.getList().size() < PAGE_SIZE) break;
        }
        return result;
    }

    private Map<String, Object> desired(
            EngineTokens.Claims claims,
            ApplicationUi.EngineWriteback target,
            Map<String, String> mapping,
            Map<String, FieldDefinition> fields,
            ApplicationUi.EngineLibrary materials,
            EngineLink.BomRow row) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put(target.recordFieldId(), claims.rec());
        values.put(target.keyFieldId(), row.key());
        String referenced = row.libraryRef() == null ? null : row.libraryRef().recordId();
        if (referenced != null
                && materials != null
                && !blank(row.libraryRef().objectId())
                && !Objects.equals(row.libraryRef().objectId(), materials.objectId())
                && "MATERIAL".equals(row.objectKind())) throw invalid("材料不属于本区块配置的材料库");
        put(
                values,
                mapping.get("quantity"),
                quantity(row.quantity(), fields.get(mapping.get("quantity"))));
        put(values, mapping.get("name"), row.name());
        put(values, mapping.get("unit"), row.unit());
        put(values, mapping.get("methodCode"), row.methodCode());
        put(values, mapping.get("basis"), row.basis());
        put(
                values,
                mapping.get("space"),
                row.spaceIds() == null ? null : String.join("、", row.spaceIds()));
        boolean material =
                "MATERIAL".equals(row.objectKind()) && referenced != null && materials != null;
        if (!blank(mapping.get("material")))
            values.put(mapping.get("material"), material ? referenced : null);
        if (!blank(mapping.get("unitPrice"))) {
            // 单价不信任引擎传值：按材料引用读材料库记录（同样受该用户的读权限约束）。
            Object price = null;
            String priceField =
                    materials == null || materials.fields() == null
                            ? null
                            : materials.fields().get("unitPrice");
            if (material && !blank(priceField))
                price =
                        records.get(claims.app(), materials.objectId(), referenced, claims.uid())
                                .record()
                                .values()
                                .get(priceField);
            values.put(mapping.get("unitPrice"), price);
        }
        return values;
    }

    private static Object quantity(BigDecimal value, FieldDefinition field) {
        if (value == null) return null;
        if (field != null && field.scale() != null && field.scale() >= 0)
            return value.setScale(field.scale(), RoundingMode.HALF_UP);
        return value;
    }

    private static void put(Map<String, Object> values, String field, Object value) {
        if (!blank(field)) values.put(field, value);
    }

    private static boolean same(ApplicationRecords.Row row, Map<String, Object> values) {
        for (Map.Entry<String, Object> entry : values.entrySet())
            if (!Objects.equals(
                    normalize(row.values().get(entry.getKey())), normalize(entry.getValue())))
                return false;
        return true;
    }

    private static String normalize(Object value) {
        if (value == null) return null;
        if (value instanceof Number number) {
            try {
                return new BigDecimal(number.toString()).stripTrailingZeros().toPlainString();
            } catch (NumberFormatException e) {
                return number.toString();
            }
        }
        String text = value.toString();
        return text.isEmpty() ? null : text;
    }

    private static String text(ApplicationRecords.Row row, String field) {
        if (blank(field)) return null;
        String display = row.displayValues() == null ? null : row.displayValues().get(field);
        return !blank(display) ? display : string(row.values().get(field));
    }

    private static Object raw(ApplicationRecords.Row row, String field) {
        return blank(field) ? null : row.values().get(field);
    }

    private static String string(Object value) {
        return value == null ? null : value.toString();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static ServiceException forbidden(String message) {
        return new ServiceException(403, message);
    }
}

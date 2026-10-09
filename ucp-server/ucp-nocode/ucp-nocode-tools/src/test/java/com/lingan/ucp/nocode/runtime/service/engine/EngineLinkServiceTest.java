package com.lingan.ucp.nocode.runtime.service.engine;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.lingan.ucp.framework.common.exception.ServiceException;
import com.lingan.ucp.framework.common.pojo.PageResult;
import com.lingan.ucp.module.system.api.user.AdminUserApi;
import com.lingan.ucp.module.system.api.user.dto.AdminUserRespDTO;
import com.lingan.ucp.nocode.api.ApplicationAuthorization;
import com.lingan.ucp.nocode.api.ApplicationCenter;
import com.lingan.ucp.nocode.api.ApplicationRecords;
import com.lingan.ucp.nocode.api.ApplicationUi;
import com.lingan.ucp.nocode.api.DataCenter;
import com.lingan.ucp.nocode.api.EngineLink;
import com.lingan.ucp.nocode.api.FieldDefinition;
import com.lingan.ucp.nocode.application.service.application.ApplicationService;
import com.lingan.ucp.nocode.application.service.resource.ApplicationPageBindings;
import com.lingan.ucp.nocode.application.service.resource.ApplicationResourceValidator;
import com.lingan.ucp.nocode.runtime.service.record.RecordService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** 设计引擎系统侧（laneEG）：签发按记录权限只收不放、engine-link 每次实时复查、写回幂等且只动本记录的引擎行。 */
class EngineLinkServiceTest {
    static final long NOW = 1_800_000_000L;
    static final String APP = "101",
            SITE_OBJECT = "202",
            SITE = "303",
            PAGE = "page-1",
            NODE = "node-1";
    static final String LIB = "77", BOM = "88";

    @TempDir Path dir;
    EngineLinkService service;
    EngineTokens tokens;
    RecordService records;
    AdminUserApi users;
    ApplicationResourceValidator resources;
    ApplicationUi.Page page;

    static ApplicationRecords.Row row(
            String id, String rev, Map<String, Object> values, Set<String> actions) {
        return new ApplicationRecords.Row(
                id,
                rev,
                values,
                new ApplicationAuthorization.Capabilities(
                        actions, Set.of(), Set.of(), Set.of(), Set.of()),
                Map.of());
    }

    static ApplicationRecords.Aggregate aggregate(ApplicationRecords.Row row) {
        return new ApplicationRecords.Aggregate(row, Map.of());
    }

    ApplicationUi.EngineBlock engine() {
        return new ApplicationUi.EngineBlock(
                null,
                new ApplicationUi.EngineLibrary(
                        LIB,
                        Map.of("name", "lib-name", "unit", "lib-unit", "unitPrice", "lib-price")),
                null,
                new ApplicationUi.EngineWriteback(
                        BOM,
                        "bom-site",
                        "bom-key",
                        Map.of(
                                "quantity", "bom-qty",
                                "material", "bom-material",
                                "unitPrice", "bom-price",
                                "name", "bom-name")));
    }

    @BeforeEach
    void setUp() throws Exception {
        Path key = dir.resolve("k");
        Files.writeString(key, "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=");
        tokens = new EngineTokens();
        tokens.configure(key.toString(), 900, () -> NOW);
        records = mock(RecordService.class);
        users = mock(AdminUserApi.class);
        AdminUserRespDTO user = new AdminUserRespDTO();
        user.setId(7L);
        user.setNickname("张三");
        user.setStatus(0);
        when(users.getUser(7L)).thenReturn(user);
        ApplicationService applications = mock(ApplicationService.class);
        ApplicationCenter.Resource resource =
                new ApplicationCenter.Resource(PAGE, "PAGE", "site", "工事详情", Map.of());
        when(applications.published(APP))
                .thenReturn(
                        new ApplicationCenter.Published(
                                null,
                                1,
                                "c",
                                new ApplicationCenter.Definition(List.of(), List.of(resource)),
                                List.of()));
        page =
                new ApplicationUi.Page(
                        List.of(
                                new ApplicationUi.Node(
                                        NODE, "ENGINE", null, null, "设计", null, List.of(), null,
                                        null, null, null, null, null, null, engine())),
                        SITE_OBJECT,
                        2);
        resources = mock(ApplicationResourceValidator.class);
        when(resources.decode(any(), eq(ApplicationUi.Page.class))).thenAnswer(i -> page);
        service = new EngineLinkService();
        ReflectionTestUtils.setField(service, "tokens", tokens);
        ReflectionTestUtils.setField(service, "applications", applications);
        ReflectionTestUtils.setField(service, "resourceValidator", resources);
        ReflectionTestUtils.setField(service, "pageBindings", new ApplicationPageBindings());
        ReflectionTestUtils.setField(service, "records", records);
        ReflectionTestUtils.setField(service, "users", users);
        ReflectionTestUtils.setField(service, "defaultUrl", "/engine01/");
        DataCenter.Definition bomDefinition = mock(DataCenter.Definition.class);
        when(bomDefinition.fields())
                .thenReturn(
                        List.of(
                                new FieldDefinition(
                                        "q", "bom-qty", "qty", "数量", "DECIMAL", null, 12, 2, false,
                                        false, 1)));
        ApplicationRecords.Model model = mock(ApplicationRecords.Model.class);
        when(model.object()).thenReturn(bomDefinition);
        when(records.model(APP, BOM, 7L)).thenReturn(model);
    }

    void siteActions(String... actions) {
        when(records.get(APP, SITE_OBJECT, SITE, 7L))
                .thenReturn(aggregate(row(SITE, "1", Map.of(), Set.of(actions))));
    }

    String issue() {
        return service.issue(new EngineLink.Issue(APP, PAGE, NODE, SITE), 7L).token();
    }

    @Test
    void issueBindsTheRecordAndDerivesWritabilityFromUpdateRight() {
        siteActions("READ", "UPDATE");
        EngineLink.Issued issued = service.issue(new EngineLink.Issue(APP, PAGE, NODE, SITE), 7L);
        assertThat(issued.writable()).isTrue();
        assertThat(issued.project()).isEqualTo("101/202/303");
        assertThat(issued.engineUrl()).isEqualTo("/engine01/");
        assertThat(issued.expiresAt()).isEqualTo(NOW + 900);
        EngineTokens.Claims claims = tokens.verify(issued.token());
        assertThat(claims.rec()).isEqualTo(SITE);
        assertThat(claims.writable()).isTrue();
        siteActions("READ");
        assertThat(tokens.verify(issue()).writable()).isFalse();
    }

    @Test
    void issueRefusesUnreadableRecordsAndNonEngineNodes() {
        when(records.get(APP, SITE_OBJECT, SITE, 7L))
                .thenThrow(new ServiceException(1_050_000_002, "记录不存在或无权访问"));
        assertThatThrownBy(() -> issue()).hasMessageContaining("无权访问");
        assertThatThrownBy(() -> service.issue(new EngineLink.Issue(APP, PAGE, "other", SITE), 7L))
                .hasMessageContaining("设计引擎区块不存在");
        page = new ApplicationUi.Page(page.nodes(), null, 2);
        assertThatThrownBy(() -> issue()).hasMessageContaining("当前记录对象");
    }

    @Test
    void readOnlyTokenCannotWriteBackAndRevokedUpdateRightIsRecheckedLive() {
        siteActions("READ");
        String readOnly = issue();
        assertThatThrownBy(() -> service.bom(readOnly, new EngineLink.BomCommand(List.of())))
                .hasMessageContaining("只读");
        siteActions("READ", "UPDATE"); // 签发后才拿到编辑权：只读令牌仍不能写（只收不放）
        assertThatThrownBy(() -> service.bom(readOnly, new EngineLink.BomCommand(List.of())))
                .hasMessageContaining("只读");
        String writable = issue();
        siteActions("READ"); // 编辑权在令牌签发后被收回
        assertThatThrownBy(() -> service.bom(writable, new EngineLink.BomCommand(List.of())))
                .hasMessageContaining("只读");
        verify(records, never()).save(any(), anyLong());
        AdminUserRespDTO disabled = users.getUser(7L);
        disabled.setStatus(1);
        assertThatThrownBy(() -> service.library(writable, "MATERIAL", null, 1))
                .hasMessageContaining("停用");
    }

    @Test
    void libraryReadsWithTheTokenUserAndMapsFields() {
        siteActions("READ");
        when(records.page(any(), eq(7L)))
                .thenReturn(
                        new PageResult<>(
                                List.of(
                                        row(
                                                "9001",
                                                "1",
                                                Map.of(
                                                        "lib-name",
                                                        "复合木地板",
                                                        "lib-unit",
                                                        "㎡",
                                                        "lib-price",
                                                        new BigDecimal("4800")),
                                                Set.of())),
                                1L));
        EngineLink.LibraryPage page = service.library(issue(), "MATERIAL", "木", 1);
        assertThat(page.list())
                .singleElement()
                .extracting(
                        EngineLink.LibraryItem::recordId,
                        EngineLink.LibraryItem::name,
                        EngineLink.LibraryItem::unit,
                        EngineLink.LibraryItem::unitPrice)
                .containsExactly("9001", "复合木地板", "㎡", new BigDecimal("4800"));
        ArgumentCaptor<ApplicationRecords.Query> query =
                ArgumentCaptor.forClass(ApplicationRecords.Query.class);
        verify(records).page(query.capture(), eq(7L));
        assertThat(query.getValue().objectId()).isEqualTo(LIB);
        assertThat(query.getValue().search()).isEqualTo("木");
        assertThatThrownBy(() -> service.library(issue(), "COMPONENT", null, 1))
                .hasMessageContaining("构件库");
    }

    EngineLink.BomRow bomRow(String key, String quantity, String unitPrice) {
        return new EngineLink.BomRow(
                key,
                "MATERIAL",
                "LIB-9001",
                new EngineLink.LibraryRef(LIB, "9001"),
                "复合木地板",
                "FL-01",
                List.of(),
                new BigDecimal(quantity),
                "㎡",
                unitPrice == null ? null : new BigDecimal(unitPrice),
                "MEASURED",
                "basis",
                List.of("FLOOR-L"));
    }

    @Test
    void writeBackIsIdempotentTouchesOnlyEngineRowsOfThisRecordAndTakesPriceFromLibrary() {
        siteActions("READ", "UPDATE");
        String token = issue();
        when(records.get(APP, LIB, "9001", 7L))
                .thenReturn(
                        aggregate(
                                row(
                                        "9001",
                                        "1",
                                        Map.of("lib-price", new BigDecimal("4800")),
                                        Set.of())));
        Map<String, Object> unchanged =
                new HashMap<>(
                        Map.of(
                                "bom-site",
                                303L,
                                "bom-key",
                                "MAT:SAME",
                                "bom-qty",
                                new BigDecimal("5.00"),
                                "bom-material",
                                9001L,
                                "bom-price",
                                new BigDecimal("4800"),
                                "bom-name",
                                "复合木地板"));
        when(records.page(any(), eq(7L)))
                .thenReturn(
                        new PageResult<>(
                                List.of(
                                        row(
                                                "1",
                                                "r1",
                                                Map.of(
                                                        "bom-site",
                                                        303L,
                                                        "bom-key",
                                                        "MAT:LIB-9001:FL-01",
                                                        "bom-qty",
                                                        new BigDecimal("3")),
                                                Set.of()),
                                        row(
                                                "2",
                                                "r2",
                                                Map.of("bom-site", 303L, "bom-key", "MAT:GONE"),
                                                Set.of()),
                                        row(
                                                "3",
                                                "r3",
                                                Map.of("bom-site", 303L, "bom-name", "人工行"),
                                                Set.of()),
                                        row(
                                                "4",
                                                "r4",
                                                Map.of(
                                                        "bom-site",
                                                        999L,
                                                        "bom-key",
                                                        "MAT:OTHER-SITE"),
                                                Set.of()),
                                        row("5", "r5", unchanged, Set.of())),
                                5L));
        EngineLink.BomResult result =
                service.bom(
                        token,
                        new EngineLink.BomCommand(
                                List.of(
                                        bomRow("MAT:LIB-9001:FL-01", "17.655", "1"),
                                        bomRow("MAT:SAME", "5", null),
                                        bomRow("MAT:NEW", "2", "999999"))));
        assertThat(result)
                .extracting(
                        EngineLink.BomResult::created,
                        EngineLink.BomResult::updated,
                        EngineLink.BomResult::deleted,
                        EngineLink.BomResult::unchanged)
                .containsExactly(1, 1, 1, 1);
        assertThat(result.errors()).isEmpty();
        ArgumentCaptor<ApplicationRecords.Save> saves =
                ArgumentCaptor.forClass(ApplicationRecords.Save.class);
        verify(records, times(2)).save(saves.capture(), eq(7L));
        ApplicationRecords.Save update = saves.getAllValues().get(0);
        assertThat(update.id()).isEqualTo("1");
        assertThat(update.expectedRevision()).isEqualTo("r1");
        assertThat(update.values())
                .containsEntry("bom-site", SITE)
                .containsEntry("bom-qty", new BigDecimal("17.66"))
                .containsEntry("bom-material", "9001")
                .containsEntry("bom-price", new BigDecimal("4800"));
        assertThat(saves.getAllValues().get(1).id()).isNull();
        assertThat(saves.getAllValues().get(1).values())
                .containsEntry("bom-price", new BigDecimal("4800"));
        ArgumentCaptor<ApplicationRecords.Delete> deletes =
                ArgumentCaptor.forClass(ApplicationRecords.Delete.class);
        verify(records).delete(deletes.capture(), eq(7L));
        assertThat(deletes.getValue().id()).isEqualTo("2");
        ArgumentCaptor<ApplicationRecords.Query> query =
                ArgumentCaptor.forClass(ApplicationRecords.Query.class);
        verify(records).page(query.capture(), eq(7L));
        assertThat(query.getValue().equal()).containsExactly(Map.entry("bom-site", SITE));
    }

    @Test
    void rejectsDuplicateKeysAndRowFailuresAreReportedPerRow() {
        siteActions("READ", "UPDATE");
        String token = issue();
        when(records.page(any(), eq(7L))).thenReturn(new PageResult<>(List.of(), 0L));
        assertThatThrownBy(
                        () ->
                                service.bom(
                                        token,
                                        new EngineLink.BomCommand(
                                                List.of(
                                                        bomRow("K", "1", null),
                                                        bomRow("K", "2", null)))))
                .hasMessageContaining("重复");
        when(records.get(APP, LIB, "9001", 7L))
                .thenThrow(new ServiceException(1_050_000_002, "材料库记录不可见"));
        EngineLink.BomResult result =
                service.bom(token, new EngineLink.BomCommand(List.of(bomRow("K", "1", null))));
        assertThat(result.errors())
                .singleElement()
                .extracting(EngineLink.BomError::message)
                .isEqualTo("材料库记录不可见");
        verify(records, never()).save(any(), anyLong());
    }
}

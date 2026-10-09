package com.richuang.os.nocode.tools;

import static com.richuang.os.nocode.tools.NocodeIntegrationSupport.*;

import static org.assertj.core.api.Assertions.*;

import com.richuang.os.framework.common.exception.ServiceException;
import com.richuang.os.nocode.api.*;
import com.richuang.os.nocode.report.service.dataset.ReportDatasetSourceService;
import com.richuang.os.nocode.report.service.dataset.ReportDatasetSourceServiceImpl;

import org.junit.jupiter.api.*;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

/** 使用当前开发库发布真实对象并验证来源绑定；每例回滚，仅清理自身随机前缀夹具。 */
class ReportDatasetSourceIntegrationTest {
    private NocodeIntegrationSupport fixture;
    private AnnotationConfigApplicationContext reportContext;
    private ReportDatasetSourceService sources;
    private DataObjectApi objects;

    @BeforeAll
    static void open() throws Exception {
        connect();
    }

    @AfterAll
    static void shutdown() {
        close();
    }

    @BeforeEach
    void setup() {
        fixture = new NocodeIntegrationSupport();
        fixture.name();
        reportContext = new AnnotationConfigApplicationContext();
        reportContext.setParent(servicesContext);
        reportContext.register(ReportDatasetSourceServiceImpl.class);
        reportContext.refresh();
        sources = reportContext.getBean(ReportDatasetSourceService.class);
        objects = servicesContext.getBean(DataObjectApi.class);
    }

    @AfterEach
    void cleanup() {
        reportContext.close();
        fixture.clean();
    }

    @Test
    void resolvesActualPublishedSnapshotAndRejectsChecksumTampering() {
        rollback(
                () -> {
                    ObjectDraft draft = fixture.create("dataset");
                    DataCenter.PublishPlan plan =
                            publisher.plan(
                                    new DataCenter.Revision(draft.id(), draft.lockVersion(), null),
                                    10001);
                    assertThat(plan.checks()).noneMatch(DataCenter.Check::blocking);
                    assertThat(
                                    publisher
                                            .execute(
                                                    new DataCenter.ExecutePlan(plan.id(), "报表来源验收"),
                                                    10001)
                                            .state())
                            .isEqualTo("SUCCEEDED");
                    DataObjectApi.PublishedObject published = objects.getVersion(draft.id(), null);
                    ReportDatasets.Source source =
                            source(draft, published.checksum(), published.versionNo());
                    ReportDatasets.ResolvedSource result = sources.resolve(source);
                    assertThat(result.objects()).containsExactly(source.root());
                    assertThat(result.fields().getFirst().sourceFieldId())
                            .isEqualTo(draft.fields().getFirst().id());
                    assertThat(result.fields().getFirst().type()).isEqualTo("TEXT");
                    assertThat(objects.getVersion(draft.id(), published.versionNo()))
                            .isEqualTo(published);
                    assertThatThrownBy(
                                    () ->
                                            sources.resolve(
                                                    source(
                                                            draft,
                                                            "tampered",
                                                            published.versionNo())))
                            .isInstanceOf(ServiceException.class)
                            .hasMessageContaining("校验和");
                });
    }

    @Test
    void doesNotTreatUnpublishedDraftAsAnAvailableSource() {
        rollback(
                () -> {
                    ObjectDraft draft = fixture.create("draft");
                    assertThatThrownBy(() -> sources.resolve(source(draft, "unpublished", 1)))
                            .isInstanceOf(ServiceException.class)
                            .hasMessageContaining("尚未发布");
                });
    }

    private ReportDatasets.Source source(ObjectDraft draft, String checksum, int version) {
        return new ReportDatasets.Source(
                1,
                new ReportDatasets.ObjectReference(draft.id(), version, checksum),
                List.of(),
                List.of(
                        new ReportDatasets.Field(
                                "name",
                                List.of(),
                                draft.fields().getFirst().id(),
                                "名称",
                                "DIMENSION")));
    }

    private void rollback(Runnable action) {
        new TransactionTemplate(manager)
                .executeWithoutResult(
                        tx -> {
                            try {
                                action.run();
                            } finally {
                                tx.setRollbackOnly();
                            }
                        });
    }
}

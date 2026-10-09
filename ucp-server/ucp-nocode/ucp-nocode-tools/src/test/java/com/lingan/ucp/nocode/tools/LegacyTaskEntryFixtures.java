package com.lingan.ucp.nocode.tools;

import static com.lingan.ucp.nocode.tools.NocodeIntegrationSupport.*;

import static org.assertj.core.api.Assertions.assertThat;

import com.lingan.ucp.nocode.api.ApplicationCenter.*;
import com.lingan.ucp.nocode.application.dal.dataobject.NocodeApplicationDO;
import com.lingan.ucp.nocode.application.dal.mapper.ApplicationMapper;
import com.lingan.ucp.nocode.application.service.application.ApplicationService;
import com.lingan.ucp.nocode.enums.ApplicationResourceKindEnum;

import org.springframework.transaction.support.TransactionTemplate;

/** 仅向当次测试自有应用种入退役前的历史资源；生产保存入口仍完整执行冻结校验。 */
final class LegacyTaskEntryFixtures {
    private LegacyTaskEntryFixtures() {}

    static Detail seed(ApplicationService applications, Save request, long actor) {
        if (request.definition() == null
                || request.definition().resources() == null
                || request.definition().resources().stream()
                        .noneMatch(
                                resource ->
                                        resource != null
                                                && ApplicationResourceKindEnum.TASK_ENTRY.matches(
                                                        resource.kind())))
            return applications.save(request, actor);
        assertThat(request.code()).startsWith("test_b1_");
        return new TransactionTemplate(manager)
                .execute(
                        status -> {
                            Definition definition =
                                    applications.normalize(request.definition(), false);
                            Detail current;
                            if (request.id() == null) {
                                current =
                                        applications.save(
                                                new Save(
                                                        null,
                                                        null,
                                                        request.code(),
                                                        request.name(),
                                                        request.description(),
                                                        request.icon(),
                                                        new Definition(
                                                                definition.objects(),
                                                                definition.resources().stream()
                                                                        .filter(
                                                                                resource ->
                                                                                        !ApplicationResourceKindEnum
                                                                                                .TASK_ENTRY
                                                                                                .matches(
                                                                                                        resource
                                                                                                                .kind()))
                                                                        .toList()),
                                                        request.category()),
                                                actor);
                            } else {
                                applications.requireDesigner(request.id(), actor);
                                current = applications.get(request.id());
                                assertThat(current.application().code()).isEqualTo(request.code());
                                assertThat(current.application().revision())
                                        .isEqualTo(request.expectedRevision());
                            }
                            ApplicationMapper store =
                                    servicesContext.getBean(ApplicationMapper.class);
                            NocodeApplicationDO application =
                                    store.lock(Long.parseLong(current.application().id()), true);
                            try {
                                application.setDesignJson(mapper.writeValueAsString(definition));
                            } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
                                throw new IllegalArgumentException("历史入口夹具无法序列化", failure);
                            }
                            assertThat(
                                            store.updateDraft(
                                                    application,
                                                    current.application().revision(),
                                                    Long.toString(actor)))
                                    .isEqualTo(1);
                            // 历史资源已持久化；再走正式保存完成资源依赖登记，并证明原配置可正常往返。
                            return applications.save(
                                    new Save(
                                            current.application().id(),
                                            current.application().revision() + 1,
                                            request.code(),
                                            request.name(),
                                            request.description(),
                                            request.icon(),
                                            definition,
                                            request.category()),
                                    actor);
                        });
    }
}

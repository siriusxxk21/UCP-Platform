package com.richuang.os.nocode.tools;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.richuang.os.framework.common.util.json.JsonUtils;
import com.richuang.os.module.bpm.api.task.BpmBusinessTaskBinding;
import com.richuang.os.module.bpm.api.task.BpmNodeFormBinding;
import com.richuang.os.module.bpm.controller.admin.definition.vo.model.BpmModelMetaInfoVO;
import com.richuang.os.module.bpm.controller.admin.definition.vo.model.simple.BpmSimpleModelNodeVO;
import com.richuang.os.module.bpm.dal.dataobject.definition.BpmFormDO;
import com.richuang.os.module.bpm.framework.flowable.core.util.BpmNodeFormUtils;
import com.richuang.os.module.bpm.framework.flowable.core.util.BpmnModelUtils;
import com.richuang.os.module.bpm.framework.flowable.core.util.SimpleModelUtils;
import com.richuang.os.module.bpm.service.definition.BpmFormService;
import com.richuang.os.module.bpm.service.definition.BpmNodeFormServiceImpl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/** 发布解析、SIMPLE 转换、配置往返和提交边界；不依赖伪造的页面保存结果。 */
class BpmNodeFormBindingTest {
    @Test
    void materialReviewSurvivesNoneInheritanceAndInvalidPolicyCannotPublish() {
        String binding =
                "{\"mode\":\"OVERRIDE\",\"source\":{\"kind\":\"NONE\"},\"materialReview\":{\"scope\":\"PREVIOUS\",\"access\":\"TASK\"}}";
        var compiled = service.resolveForDeployment(xml(binding), model);
        assertThat(
                        BpmNodeFormUtils.resolved(node(compiled))
                                .path("materialReview")
                                .path("access")
                                .asText())
                .isEqualTo("TASK");
        assertThat(
                        BpmNodeFormUtils.resolved(
                                        node(service.resolveForDeployment(compiled, model)))
                                .path("source")
                                .path("kind")
                                .asText())
                .isEqualTo("NONE");
        for (String invalid :
                List.of(
                        binding.replace("TASK", "PUBLIC"),
                        binding.replace("PREVIOUS", "ALL"),
                        binding.replace(
                                "\"access\":\"TASK\"", "\"access\":\"TASK\",\"extra\":true"))) {
            assertThatThrownBy(() -> service.resolveForDeployment(xml(invalid), model))
                    .hasMessageContaining("材料查阅配置");
        }
        var automatic = BpmnModelUtils.getBpmnModel(xml(binding));
        BpmnModelUtils.addExtensionElement(automatic.getFlowElement("work"), "approveType", "2");
        assertThatThrownBy(
                        () ->
                                service.resolveForDeployment(
                                        new org.flowable.bpmn.converter.BpmnXMLConverter()
                                                .convertToXML(automatic),
                                        model))
                .hasMessageContaining("人工办理节点");
    }

    @Test
    void noStarterFormResolvesEmptyInheritedNodeAndRejectsInjectedFields() {
        model.setFormType(0);
        model.setFormId(1L); // 旧引用不能重新激活。
        var compiled = service.resolveForDeployment(xml("{\"mode\":\"INHERIT\"}"), model);
        var resolved = BpmNodeFormUtils.resolved(node(compiled));
        assertThat(resolved.path("source").path("kind").asText()).isEqualTo("NONE");
        assertThat(service.getDeployedForm(node(compiled))).isNull();
        BpmNodeFormUtils.validateSubmission(
                BpmnModelUtils.getBpmnModel(compiled), "work", Map.of());
        assertThatThrownBy(
                        () ->
                                BpmNodeFormUtils.validateSubmission(
                                        BpmnModelUtils.getBpmnModel(compiled),
                                        "work",
                                        Map.of("request", "unexpected")))
                .hasMessageContaining("不能提交额外业务字段");
        verifyNoInteractions(forms);
    }

    @Test
    void explicitEmptyNodeDoesNotInheritStarterFormAndIndependentFormSurvivesEmptyStarter() {
        var empty =
                service.resolveForDeployment(
                        xml("{\"mode\":\"OVERRIDE\",\"source\":{\"kind\":\"NONE\"}}"), model);
        assertThat(BpmNodeFormUtils.resolved(node(empty)).path("source").path("kind").asText())
                .isEqualTo("NONE");
        model.setFormType(0);
        var independent =
                service.resolveForDeployment(
                        xml(
                                "{\"mode\":\"OVERRIDE\",\"source\":{\"kind\":\"FLOW_FORM\",\"formId\":\"2\"}}"),
                        model);
        assertThat(service.getDeployedForm(node(independent)).getId()).isEqualTo(2L);
    }

    @Test
    void businessCompletionCannotUseApprovalOrEmptySource() {
        assertThatThrownBy(
                        () ->
                                service.resolveForDeployment(
                                        xml("{\"mode\":\"INHERIT\",\"taskMode\":\"BUSINESS\"}"),
                                        model))
                .hasMessageContaining("业务任务必须绑定");
        assertThatThrownBy(
                        () ->
                                service.resolveForDeployment(
                                        xml(
                                                "{\"mode\":\"OVERRIDE\",\"taskMode\":\"APPROVAL\",\"source\":{\"kind\":\"APPLICATION_RESOURCE\"}}"),
                                        model))
                .hasMessageContaining("不能作为普通审批放行");
        assertThatThrownBy(
                        () ->
                                service.resolveForDeployment(
                                        xml("{\"mode\":\"INHERIT\",\"taskMode\":\"UNKNOWN\"}"),
                                        model))
                .hasMessageContaining("节点完成方式尚未支持");
    }

    @Test
    void emptyFormPublicationIsExplicitAndUnknownTypesStillFail() {
        var models = new com.richuang.os.module.bpm.service.definition.BpmModelServiceImpl();
        ReflectionTestUtils.setField(models, "bpmFormService", forms);
        model.setFormType(0);
        assertThat((Object) ReflectionTestUtils.invokeMethod(models, "validateFormConfig", model))
                .isNull();
        model.setFormType(99);
        model.setFormCustomCreatePath("/legacy");
        model.setFormCustomViewPath("/legacy");
        assertThatThrownBy(
                        () -> ReflectionTestUtils.invokeMethod(models, "validateFormConfig", model))
                .isInstanceOf(RuntimeException.class);
        model.setFormType(10);
        model.setFormId(null);
        assertThatThrownBy(
                        () -> ReflectionTestUtils.invokeMethod(models, "validateFormConfig", model))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void noFormModelSaveClearsLegacyFormReferences() {
        var request =
                new com.richuang.os.module.bpm.controller.admin.definition.vo.model
                        .BpmModelSaveReqVO();
        request.setFormType(0);
        request.setFormId(42L);
        request.setFormCustomCreatePath("/old-create");
        request.setFormCustomViewPath("/old-view");
        var saved = new org.flowable.engine.impl.persistence.entity.ModelEntityImpl();
        com.richuang.os.module.bpm.convert.definition.BpmModelConvert.INSTANCE.copyToModel(
                saved, request);
        var metadata = JsonUtils.parseObject(saved.getMetaInfo(), BpmModelMetaInfoVO.class);
        assertThat(metadata.getFormType()).isZero();
        assertThat(metadata.getFormId()).isNull();
        assertThat(metadata.getFormCustomCreatePath()).isNull();
        assertThat(metadata.getFormCustomViewPath()).isNull();
    }

    @Test
    void simpleRoundtripKeepsUnrecognizedNodeProperties() {
        String raw = "{\"id\":\"end\",\"type\":1,\"name\":\"结束\",\"future\":{\"enabled\":true}}";
        var model = JsonUtils.parseObject(raw, BpmSimpleModelNodeVO.class);
        var saved = JsonUtils.parseObject(JsonUtils.toJsonString(model), JsonNode.class);
        assertThat(saved.path("future").path("enabled").asBoolean()).isTrue();
        assertThat(saved.has("extraProperties")).isFalse();
    }

    BpmNodeFormServiceImpl service;
    BpmFormService forms;
    BpmModelMetaInfoVO model;

    @BeforeEach
    void setup() {
        service = new BpmNodeFormServiceImpl();
        forms = mock(BpmFormService.class);
        ReflectionTestUtils.setField(service, "formService", forms);
        model = new BpmModelMetaInfoVO();
        model.setFormType(10);
        model.setFormId(1L);
        when(forms.getForm(1L)).thenReturn(form(1L, "申请表", "request"));
        when(forms.getForm(2L)).thenReturn(form(2L, "办理表", "result"));
    }

    BpmFormDO form(long id, String name, String field) {
        var form = new BpmFormDO();
        form.setId(id);
        form.setName(name);
        form.setConf("{}");
        form.setFields(
                List.of(
                        "{\"type\":\"input\",\"field\":\""
                                + field
                                + "\",\"validate\":[{\"required\":true}]}"));
        return form;
    }

    byte[] xml(String binding) {
        return ("<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\" xmlns:n=\""
                        + BpmNodeFormBinding.NAMESPACE
                        + "\" targetNamespace=\"test\"><process id=\"test\""
                        + " isExecutable=\"true\"><startEvent id=\"start\"/><sequenceFlow id=\"a\""
                        + " sourceRef=\"start\" targetRef=\"work\"/><userTask id=\"work\""
                        + " name=\"办理\" n:configuration=\""
                        + binding.replace("&", "&amp;").replace("\"", "&quot;")
                        + "\"/><sequenceFlow id=\"b\" sourceRef=\"work\""
                        + " targetRef=\"end\"/><endEvent id=\"end\"/></process></definitions>")
                .getBytes(StandardCharsets.UTF_8);
    }

    org.flowable.bpmn.model.FlowElement node(byte[] bytes) {
        return BpmnModelUtils.getBpmnModel(bytes).getFlowElement("work");
    }

    @Test
    void inheritsStarterAtPublicationWithoutChangingDraft() {
        var draft = xml("{\"mode\":\"INHERIT\"}");
        var old = service.resolveForDeployment(draft, model);
        model.setFormId(2L);
        var next = service.resolveForDeployment(draft, model);
        assertThat(service.getDeployedForm(node(old)).getId()).isEqualTo(1L);
        assertThat(service.getDeployedForm(node(next)).getId()).isEqualTo(2L);
        assertThat(node(draft).getAttributeValue(BpmNodeFormBinding.NAMESPACE, "resolved"))
                .isNull();
    }

    @Test
    void overrideKeepsSelectedFormWhenStarterChanges() {
        var draft =
                xml(
                        "{\"mode\":\"OVERRIDE\",\"source\":{\"kind\":\"FLOW_FORM\",\"formId\":\"2\"},\"future\":{\"enabled\":true}}");
        var old = service.resolveForDeployment(draft, model);
        model.setFormId(999L);
        var next = service.resolveForDeployment(draft, model);
        assertThat(service.getDeployedForm(node(next)).getId()).isEqualTo(2L);
        assertThat(BpmNodeFormUtils.resolved(node(old)).path("future").path("enabled").asBoolean())
                .isTrue();
    }

    @Test
    void deployedSnapshotSurvivesFormEditOrDeletion() {
        var deployed =
                service.resolveForDeployment(
                        xml(
                                "{\"mode\":\"OVERRIDE\",\"source\":{\"kind\":\"FLOW_FORM\",\"formId\":\"2\"}}"),
                        model);
        when(forms.getForm(2L)).thenReturn(null);
        assertThat(service.getDeployedForm(node(deployed)).getName()).isEqualTo("办理表");
        assertThat(service.getDeployedForm(node(deployed)).getFields().get(0)).contains("result");
    }

    @Test
    void simpleNodesKeepBindingAndConvertToTheSameProtocol() {
        var root =
                JsonUtils.parseObject(
                        "{\"id\":\"StartUserNode\",\"type\":10,\"name\":\"发起人\",\"childNode\":{\"id\":\"work\",\"type\":11,\"name\":\"办理\",\"approveType\":1,\"candidateStrategy\":36,\"approveMethod\":1,\"assignStartUserHandlerType\":1,\"formBinding\":{\"mode\":\"OVERRIDE\",\"source\":{\"kind\":\"FLOW_FORM\",\"formId\":\"2\"},\"extra\":true},\"childNode\":{\"id\":\"end\",\"type\":1,\"name\":\"结束\"}}}",
                        BpmSimpleModelNodeVO.class);
        var draft =
                BpmnModelUtils.getBpmnXml(
                                SimpleModelUtils.buildBpmnModel("simple_form", "简单流程", root))
                        .getBytes(StandardCharsets.UTF_8);
        assertThat(JsonUtils.toJsonString(root)).contains("\"extra\":true");
        var deployed = service.resolveForDeployment(draft, model);
        assertThat(service.getDeployedForm(node(deployed)).getId()).isEqualTo(2L);
    }

    @Test
    void applicationBindingBecomesTheExistingBusinessTaskProtocolAndCanBeRestored() {
        var config =
                "{\"resource\":{\"applicationId\":\"1\",\"applicationVersion\":9,\"applicationChecksum\":\"hash\",\"resourceKind\":\"FORM\",\"resourceId\":\"r\"},\"objectId\":\"10\",\"operation\":\"CREATE\",\"extra\":true}";
        var deployed =
                service.resolveForDeployment(
                        xml(
                                "{\"mode\":\"OVERRIDE\",\"source\":{\"kind\":\"APPLICATION_RESOURCE\",\"configuration\":"
                                        + config
                                        + "}}"),
                        model);
        assertThat(node(deployed).getAttributeValue(BpmBusinessTaskBinding.NAMESPACE, "handler"))
                .isEqualTo("nocode");
        var restored = service.resolveForDeployment(deployed, model);
        assertThat(
                        JsonUtils.parseObject(
                                node(restored)
                                        .getAttributeValue(
                                                BpmBusinessTaskBinding.NAMESPACE, "configuration"),
                                JsonNode.class))
                .isEqualTo(JsonUtils.parseObject(config, JsonNode.class));
    }

    @Test
    void damagedOrUnknownBindingsCannotSilentlyPublish() {
        for (String binding :
                List.of(
                        "null",
                        "[]",
                        "{",
                        "{}",
                        "{\"mode\":\"INHERIT\",\"source\":{}}",
                        "{\"mode\":\"OVERRIDE\",\"source\":{\"kind\":\"FUTURE\"}}"))
            assertThatThrownBy(() -> service.resolveForDeployment(xml(binding), model))
                    .hasMessageContaining("表单配置");
    }

    @Test
    void unavailableFormAndUnregisteredRouteCannotPublish() {
        assertThatThrownBy(
                        () ->
                                service.resolveForDeployment(
                                        xml(
                                                "{\"mode\":\"OVERRIDE\",\"source\":{\"kind\":\"FLOW_FORM\",\"formId\":\"999\"}}"),
                                        model))
                .hasMessageContaining("不存在");
        assertThatThrownBy(
                        () ->
                                service.resolveForDeployment(
                                        xml(
                                                "{\"mode\":\"OVERRIDE\",\"source\":{\"kind\":\"SYSTEM_ROUTE\",\"createPath\":\"/any\"}}"),
                                        model))
                .hasMessageContaining("尚未注册");
    }

    @Test
    void independentSubmissionRejectsExtraAndMissingRequiredFields() {
        var bpmn =
                BpmnModelUtils.getBpmnModel(
                        service.resolveForDeployment(
                                xml(
                                        "{\"mode\":\"OVERRIDE\",\"source\":{\"kind\":\"FLOW_FORM\",\"formId\":\"2\"}}"),
                                model));
        assertThatThrownBy(
                        () ->
                                BpmNodeFormUtils.validateSubmission(
                                        bpmn, "work", Map.of("admin", true)))
                .hasMessageContaining("不可写字段");
        assertThatThrownBy(() -> BpmNodeFormUtils.validateSubmission(bpmn, "work", Map.of()))
                .hasMessageContaining("必填字段");
        assertThatCode(
                        () ->
                                BpmNodeFormUtils.validateSubmission(
                                        bpmn, "work", Map.of("result", "完成")))
                .doesNotThrowAnyException();
    }
}

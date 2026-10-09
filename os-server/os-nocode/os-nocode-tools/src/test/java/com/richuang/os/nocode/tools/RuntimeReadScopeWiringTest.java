package com.richuang.os.nocode.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.richuang.os.nocode.api.ApplicationRecords;
import com.richuang.os.nocode.controller.admin.ApplicationRuntimeController;
import com.richuang.os.nocode.metadata.service.request.ReadRequestMemo;
import com.richuang.os.nocode.runtime.service.application.ApplicationRuntimeService;
import com.richuang.os.nocode.runtime.service.record.RecordService;
import com.richuang.os.nocode.runtime.service.report.ApplicationReportService;
import com.richuang.os.nocode.runtime.service.view.DataViewService;
import com.richuang.os.nocode.web.NocodeAccess;
import com.richuang.os.nocode.web.RecordExcelService;
import com.richuang.os.nocode.web.StrictRequestDecoder;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;

/** 运行端接口与只读作用域的接线：只读接口在作用域内调用服务，写入、导入、导出等接口不开启作用域；请求结束后线程上不残留作用域。 */
class RuntimeReadScopeWiringTest {
    private final List<Boolean> seen = new ArrayList<>();
    private final Answer<Object> observe =
            invocation -> {
                seen.add(ReadRequestMemo.active());
                return null;
            };
    private final JsonNode body = JsonNodeFactory.instance.objectNode();
    private ApplicationRuntimeController controller;
    private ApplicationRecords.Query query;

    @BeforeEach
    void setup() {
        controller = new ApplicationRuntimeController();
        NocodeAccess access = mock(NocodeAccess.class);
        when(access.actor()).thenReturn(10001L);
        StrictRequestDecoder requests = mock(StrictRequestDecoder.class);
        query = mock(ApplicationRecords.Query.class);
        when(requests.runtime(any(), any()))
                .thenAnswer(
                        invocation ->
                                invocation.getArgument(1) == ApplicationRecords.Query.class
                                        ? query
                                        : null);
        ReflectionTestUtils.setField(controller, "access", access);
        ReflectionTestUtils.setField(controller, "requests", requests);
        ReflectionTestUtils.setField(
                controller, "runtime", mock(ApplicationRuntimeService.class, observe));
        ReflectionTestUtils.setField(controller, "records", mock(RecordService.class, observe));
        ReflectionTestUtils.setField(controller, "dataViews", mock(DataViewService.class, observe));
        ReflectionTestUtils.setField(
                controller, "reports", mock(ApplicationReportService.class, observe));
        ReflectionTestUtils.setField(controller, "excel", mock(RecordExcelService.class, observe));
    }

    @Test
    void readEndpointsCallServicesInsideTheScope() {
        controller.application("1");
        controller.mine();
        controller.model("1", "2");
        controller.selection(body);
        controller.evaluateFieldRules(body);
        controller.page(body);
        when(query.reportDrill())
                .thenReturn(mock(com.richuang.os.nocode.api.ApplicationReports.Drill.class));
        controller.page(body);
        controller.viewModel("1", "2", "3");
        controller.viewChildren(body);
        controller.report(body);
        controller.reportDetails(body);
        controller.get("1", "2", "3");
        assertThat(seen).hasSize(12).containsOnly(true);
        // 作用域随请求结束：线程回到连接池后不能带着上一个请求读到的值。
        assertThat(ReadRequestMemo.active()).isFalse();
    }

    @Test
    void writeAndTransferEndpointsStayOutsideTheScope() {
        controller.save(body);
        controller.delete(body);
        controller.action(body);
        controller.saveReceipt("1", "2", "3");
        controller.formFill(body);
        controller.processRecord("nocode:1");
        assertThat(seen).hasSize(6).containsOnly(false);
        assertThat(ReadRequestMemo.active()).isFalse();
    }
}

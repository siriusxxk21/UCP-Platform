package com.lingan.ucp.nocode.tools;

import static com.lingan.ucp.nocode.tools.NocodeIntegrationSupport.*;

import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

/** 仅清理命令行明确指定、且名称/前缀均符合任务入口 HTTP 验收夹具的应用和对象。 */
public final class TaskEntryFixtureCleaner {
    private TaskEntryFixtureCleaner() {}

    public static void main(String[] ids) throws Exception {
        if (ids.length == 0) throw new IllegalArgumentException("请明确指定本次验收应用 ID");
        connect();
        try {
            for (String id : ids) {
                if (!id.matches("[1-9][0-9]{0,17}")) throw new IllegalArgumentException("应用 ID 无效");
                var app =
                        jdbc.queryForMap(
                                "SELECT app_code,app_name AS name FROM public.nocode_application"
                                        + " WHERE id=?",
                                Long.valueOf(id));
                String code = app.get("app_code").toString();
                boolean entryFixture =
                        code.matches("fa[a-z0-9]{8,16}_task") && "任务中心验收示例".equals(app.get("name"));
                boolean documentFixture =
                        code.matches("fa[a-z0-9]{8,16}_document")
                                && "采购登记与自动已办（验收）".equals(app.get("name"));
                if (!entryFixture && !documentFixture)
                    throw new IllegalArgumentException("拒绝清理非任务中心验收应用 " + id);
                var fixture = new NocodeIntegrationSupport();
                fixture.prefix = code.substring(0, code.lastIndexOf('_'));
                new TransactionTemplate(manager)
                        .executeWithoutResult(
                                status -> {
                                    jdbc.update(
                                            "DELETE FROM public.nocode_work_draft WHERE"
                                                    + " resource_json->>'applicationId'=? AND"
                                                    + " source_type='TASK_ENTRY'",
                                            id);
                                    for (String table :
                                            List.of(
                                                    "nocode_object_application_grant_log",
                                                    "nocode_object_application_grant",
                                                    "nocode_application_access",
                                                    "nocode_application_version"))
                                        jdbc.update(
                                                "DELETE FROM public."
                                                        + table
                                                        + " WHERE application_id=?",
                                                Long.valueOf(id));
                                    jdbc.update(
                                            "DELETE FROM public.nocode_application WHERE id=?",
                                            Long.valueOf(id));
                                    fixture.clean();
                                });
                System.out.println("已清理专用验收应用 " + id + "，前缀 " + fixture.prefix);
            }
        } finally {
            close();
        }
    }
}

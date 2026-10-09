package com.lingan.ucp.module.system.service.dict;

import com.baomidou.dynamic.datasource.spring.boot.autoconfigure.DynamicDataSourceAutoConfiguration;
import com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration;
import com.lingan.ucp.framework.common.exception.ServiceException;
import com.lingan.ucp.framework.mybatis.config.OsMybatisAutoConfiguration;
import com.lingan.ucp.module.system.controller.admin.dict.vo.data.DictDataPageReqVO;
import com.lingan.ucp.module.system.controller.admin.dict.vo.data.DictDataSaveReqVO;
import com.lingan.ucp.module.system.controller.admin.dict.vo.type.DictTypeSaveReqVO;

import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;

/**
 * 真实开发库字典回归入口。使用启动模块的配置及底座数据源，仅扫描字典 Mapper，所有夹具在事务结束时回滚。 将启动模块配置、系统模块及依赖置于 classpath，编译后运行本类；不会启动
 * Web 或调度服务。
 */
public class DictDatabaseVerification {
    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @ImportAutoConfiguration({
        DynamicDataSourceAutoConfiguration.class,
        DataSourceTransactionManagerAutoConfiguration.class,
        JdbcTemplateAutoConfiguration.class,
        OsMybatisAutoConfiguration.class,
        MybatisPlusAutoConfiguration.class
    })
    @Import({DictTypeServiceImpl.class, DictDataServiceImpl.class})
    static class Config {}

    public static void main(String[] args) {
        try (var context =
                new SpringApplicationBuilder(Config.class)
                        .web(WebApplicationType.NONE)
                        .logStartupInfo(false)
                        .registerShutdownHook(false)
                        .run(
                                "--os.info.base-package=com.lingan.ucp.module.system.dal.mysql.dict",
                                "--mybatis-plus.mapper-locations=classpath*:mapper/dict/*.xml",
                                "--spring.main.banner-mode=off")) {
            var types = context.getBean(DictTypeService.class);
            var data = context.getBean(DictDataService.class);
            var jdbc = context.getBean(JdbcTemplate.class);
            var tx = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
            if (args.length == 3 && "cleanup-ui".equals(args[0])) {
                Long id = Long.valueOf(args[1]);
                String code = args[2];
                check(code.startsWith("system_dict_ui_verify_"), "仅清理字典界面验收夹具");
                tx.executeWithoutResult(
                        status -> {
                            var type = types.getDictType(id);
                            check(
                                    type != null
                                            && code.equals(type.getType())
                                            && type.getName().startsWith("字典界面验收"),
                                    "夹具身份匹配");
                            var items = data.getDictDataListByDictType(code);
                            check(
                                    items.stream()
                                            .allMatch(
                                                    item ->
                                                            "界面验收项".equals(item.getLabel())
                                                                    && "0".equals(item.getValue())),
                                    "未混入用户数据");
                            if (!items.isEmpty())
                                data.deleteDictDataList(
                                        items.stream().map(item -> item.getId()).toList());
                            types.deleteDictType(id);
                        });
                System.out.println("DICT_UI_FIXTURE_CLEANED: " + id);
                return;
            }
            tx.executeWithoutResult(
                    status -> {
                        try {
                            String code =
                                    "dict_verify_" + UUID.randomUUID().toString().replace("-", "");
                            DictTypeSaveReqVO type = type(code);
                            Long typeId = types.createDictType(type);
                            type.setId(typeId);
                            Long emptyId = types.createDictType(type(code + "_empty"));
                            check(types.getDictType(typeId) != null, "创建类型");
                            fails(() -> types.createDictType(type(code)), "类型编码/名称唯一");
                            DictDataSaveReqVO high = item(code, "high", 20);
                            high.setId(data.createDictData(high));
                            DictDataSaveReqVO low = item(code, "low", 1);
                            low.setId(data.createDictData(low));
                            fails(() -> data.createDictData(item(code, "low", 2)), "数据值唯一");

                            var page = new DictDataPageReqVO();
                            page.setDictType(code);
                            page.setPageSize(1);
                            var first = data.getDictDataPage(page);
                            check(
                                    first.getTotal() == 2
                                            && first.getList()
                                                    .getFirst()
                                                    .getId()
                                                    .equals(low.getId()),
                                    "分页与升序");
                            page.setPageNo(2);
                            check(
                                    data.getDictDataPage(page)
                                            .getList()
                                            .getFirst()
                                            .getId()
                                            .equals(high.getId()),
                                    "第二页");
                            page.setPageNo(1);
                            page.setLabel("high");
                            check(data.getDictDataPage(page).getTotal() == 1, "标签筛选");

                            low.setRemark("");
                            low.setCssClass("");
                            low.setColorType("");
                            low.setStatus(1);
                            data.updateDictData(low);
                            check(
                                    "".equals(data.getDictData(low.getId()).getRemark())
                                            && ""
                                                    .equals(
                                                            data.getDictData(low.getId())
                                                                    .getCssClass()),
                                    "可选字段清空");
                            page.setLabel(null);
                            page.setStatus(1);
                            check(data.getDictDataPage(page).getTotal() == 1, "禁用筛选");

                            type.setType(code + "_renamed");
                            fails(() -> types.updateDictType(type), "编码不可变");
                            type.setType(code);
                            type.setStatus(1);
                            types.updateDictType(type);
                            fails(() -> data.createDictData(item(code, "disabled", 0)), "禁用类型禁止新增");
                            type.setStatus(0);
                            types.updateDictType(type);

                            fails(
                                    () -> types.deleteDictTypeList(List.of(emptyId, typeId)),
                                    "类型批量删除保护");
                            check(types.getDictType(emptyId) != null, "批量删除无部分成功");
                            fails(
                                    () -> data.deleteDictDataList(List.of(high.getId(), -1L)),
                                    "不存在数据拒绝删除");
                            check(data.getDictData(high.getId()) != null, "数据批量删除无部分成功");
                            data.deleteDictDataList(List.of(low.getId(), high.getId()));
                            types.deleteDictTypeList(List.of(emptyId, typeId));
                            check(
                                    types.getDictType(typeId) == null
                                            && data.getDictData(low.getId()) == null,
                                    "逻辑删除");
                            check(
                                    Boolean.TRUE.equals(
                                            jdbc.queryForObject(
                                                    "SELECT deleted=1 AND deleted_time IS NOT NULL"
                                                        + " AND update_time=deleted_time FROM"
                                                        + " system_dict_type WHERE id=?",
                                                    Boolean.class,
                                                    typeId)),
                                    "删除审计");
                            System.out.println(
                                    "DICT_DATABASE_VERIFICATION_PASSED: CRUD, pagination, sorting,"
                                        + " filters, uniqueness, immutable code, disabled type,"
                                        + " batch atomicity, audit; fixtures rolled back.");
                        } finally {
                            status.setRollbackOnly();
                        }
                    });
        }
    }

    private static DictTypeSaveReqVO type(String code) {
        var request = new DictTypeSaveReqVO();
        request.setName(code);
        request.setType(code);
        request.setStatus(0);
        return request;
    }

    private static DictDataSaveReqVO item(String code, String value, int sort) {
        var request = new DictDataSaveReqVO();
        request.setDictType(code);
        request.setLabel(value);
        request.setValue(value);
        request.setSort(sort);
        request.setStatus(0);
        request.setRemark("验证备注");
        request.setCssClass("verify");
        request.setColorType("success");
        return request;
    }

    private static void check(boolean passed, String behavior) {
        if (!passed) throw new AssertionError(behavior);
    }

    private static void fails(Runnable action, String behavior) {
        try {
            action.run();
        } catch (ServiceException expected) {
            return;
        }
        throw new AssertionError(behavior);
    }
}

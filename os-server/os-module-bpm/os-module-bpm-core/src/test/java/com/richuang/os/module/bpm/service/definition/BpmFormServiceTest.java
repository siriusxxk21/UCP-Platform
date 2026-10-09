package com.richuang.os.module.bpm.service.definition;

import static com.richuang.os.module.bpm.enums.ErrorCodeConstants.FORM_NOT_EXISTS;

import static org.assertj.core.api.Assertions.*;

import com.richuang.os.framework.common.enums.CommonStatusEnum;
import com.richuang.os.framework.common.exception.ServiceException;
import com.richuang.os.module.bpm.controller.admin.definition.vo.form.BpmFormPageReqVO;
import com.richuang.os.module.bpm.controller.admin.definition.vo.form.BpmFormSaveReqVO;
import com.richuang.os.module.bpm.dal.dataobject.definition.BpmFormDO;
import com.richuang.os.module.bpm.dal.mysql.definition.BpmFormMapper;
import com.richuang.os.module.bpm.support.BpmDatabaseTest;

import jakarta.annotation.Resource;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;

import java.util.List;

/** 表单 JSON 配置实际持久化与 CRUD 回归，复用开发库并逐项回滚。 */
@Import(BpmFormServiceImpl.class)
public class BpmFormServiceTest extends BpmDatabaseTest {
    @Resource private BpmFormServiceImpl formService;
    @Resource private BpmFormMapper formMapper;

    @Test
    void testCreateForm_success() {
        var request = request();
        var id = formService.createForm(request);
        assertThat(id).isNotNull();
        assertThat(formMapper.selectById(id))
                .usingRecursiveComparison()
                .comparingOnlyFields("id", "name", "conf", "fields", "status", "remark")
                .isEqualTo(request);
    }

    @Test
    void testUpdateForm_success() {
        var old = row("before");
        formMapper.insert(old);
        var request =
                request()
                        .setId(old.getId())
                        .setConf("{\"labelWidth\":120}")
                        .setFields(
                                List.of(
                                        "{\"type\":\"input\",\"field\":\"title\",\"title\":\"标题\"}"));
        formService.updateForm(request);
        assertThat(formMapper.selectById(old.getId()))
                .usingRecursiveComparison()
                .comparingOnlyFields("id", "name", "conf", "fields", "status", "remark")
                .isEqualTo(request);
    }

    @Test
    void testUpdateForm_notExists() {
        assertThatThrownBy(() -> formService.updateForm(request()))
                .isInstanceOf(ServiceException.class)
                .extracting("code")
                .isEqualTo(FORM_NOT_EXISTS.getCode());
    }

    @Test
    void testDeleteForm_success() {
        var old = row("delete");
        formMapper.insert(old);
        formService.deleteForm(old.getId());
        assertThat(formMapper.selectById(old.getId())).isNull();
    }

    @Test
    void testDeleteForm_notExists() {
        assertThatThrownBy(() -> formService.deleteForm(nextId()))
                .isInstanceOf(ServiceException.class)
                .extracting("code")
                .isEqualTo(FORM_NOT_EXISTS.getCode());
    }

    @Test
    void testGetFormPage() {
        var match = row("match");
        formMapper.insert(match);
        formMapper.insert(row("other"));
        var query = new BpmFormPageReqVO();
        query.setName(prefix + "_mat");
        var page = formService.getFormPage(query);
        assertThat(page.getTotal()).isEqualTo(1);
        assertThat(page.getList()).extracting(BpmFormDO::getId).containsExactly(match.getId());
        assertThat(page.getList().getFirst().getFields()).isEqualTo(match.getFields());
    }

    private BpmFormSaveReqVO request() {
        return new BpmFormSaveReqVO()
                .setId(nextId())
                .setName(prefix + "_request")
                .setStatus(CommonStatusEnum.ENABLE.getStatus())
                .setConf("{}")
                .setFields(List.of("{\"type\":\"input\",\"field\":\"name\"}"))
                .setRemark("测试表单");
    }

    private BpmFormDO row(String suffix) {
        return new BpmFormDO()
                .setId(nextId())
                .setName(prefix + "_" + suffix)
                .setStatus(CommonStatusEnum.ENABLE.getStatus())
                .setConf("{}")
                .setFields(List.of("{\"type\":\"input\",\"field\":\"name\"}"))
                .setRemark("测试表单");
    }
}

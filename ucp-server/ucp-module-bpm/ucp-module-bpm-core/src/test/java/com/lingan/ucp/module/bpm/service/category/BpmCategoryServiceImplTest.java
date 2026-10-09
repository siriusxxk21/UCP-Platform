package com.lingan.ucp.module.bpm.service.category;

import static com.lingan.ucp.module.bpm.enums.ErrorCodeConstants.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.when;

import com.lingan.ucp.framework.common.enums.CommonStatusEnum;
import com.lingan.ucp.framework.common.exception.ServiceException;
import com.lingan.ucp.module.bpm.controller.admin.definition.vo.category.BpmCategoryPageReqVO;
import com.lingan.ucp.module.bpm.controller.admin.definition.vo.category.BpmCategorySaveReqVO;
import com.lingan.ucp.module.bpm.dal.dataobject.definition.BpmCategoryDO;
import com.lingan.ucp.module.bpm.dal.mysql.category.BpmCategoryMapper;
import com.lingan.ucp.module.bpm.service.definition.BpmCategoryService;
import com.lingan.ucp.module.bpm.service.definition.BpmCategoryServiceImpl;
import com.lingan.ucp.module.bpm.service.definition.BpmModelService;
import com.lingan.ucp.module.bpm.support.BpmDatabaseTest;

import jakarta.annotation.Resource;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDateTime;

/** 分类 CRUD、分页与引用保护回归，数据库夹具逐项回滚。 */
@Import(BpmCategoryServiceImpl.class)
public class BpmCategoryServiceImplTest extends BpmDatabaseTest {
    @Resource private BpmCategoryService categoryService;
    @Resource private BpmCategoryMapper categoryMapper;
    @MockitoBean private BpmModelService modelService;

    @Test
    void testCreateCategory_success() {
        var request = request();
        var id = categoryService.createCategory(request);
        assertThat(id).isNotNull();
        assertThat(categoryMapper.selectById(id))
                .usingRecursiveComparison()
                .comparingOnlyFields("id", "name", "code", "description", "status", "sort")
                .isEqualTo(request);
    }

    @Test
    void testUpdateCategory_success() {
        var old = row("before");
        categoryMapper.insert(old);
        var request = request().setId(old.getId()).setName(prefix + "_after").setSort(8);
        categoryService.updateCategory(request);
        assertThat(categoryMapper.selectById(old.getId()))
                .usingRecursiveComparison()
                .comparingOnlyFields("id", "name", "code", "description", "status", "sort")
                .isEqualTo(request);
    }

    @Test
    void testUpdateCategory_notExists() {
        assertThatThrownBy(() -> categoryService.updateCategory(request()))
                .isInstanceOf(ServiceException.class)
                .extracting("code")
                .isEqualTo(CATEGORY_NOT_EXISTS.getCode());
    }

    @Test
    void testDeleteCategory_success() {
        var old = row("delete");
        categoryMapper.insert(old);
        when(modelService.getModelCountByCategory(old.getCode())).thenReturn(0L);
        categoryService.deleteCategory(old.getId());
        assertThat(categoryMapper.selectById(old.getId())).isNull();
    }

    @Test
    void testDeleteCategory_notExists() {
        assertThatThrownBy(() -> categoryService.deleteCategory(nextId()))
                .isInstanceOf(ServiceException.class)
                .extracting("code")
                .isEqualTo(CATEGORY_NOT_EXISTS.getCode());
    }

    @Test
    void testDeleteCategory_modelUsed() {
        var old = row("used");
        categoryMapper.insert(old);
        when(modelService.getModelCountByCategory(old.getCode())).thenReturn(1L);
        assertThatThrownBy(() -> categoryService.deleteCategory(old.getId()))
                .isInstanceOf(ServiceException.class)
                .extracting("code")
                .isEqualTo(CATEGORY_DELETE_FAIL_MODEL_USED.getCode());
        assertThat(categoryMapper.selectById(old.getId())).isNotNull();
    }

    @Test
    void testCreateCategory_duplicateNameAndCode() {
        var old = row("duplicate");
        categoryMapper.insert(old);
        assertThatThrownBy(() -> categoryService.createCategory(request().setName(old.getName())))
                .isInstanceOf(ServiceException.class)
                .extracting("code")
                .isEqualTo(CATEGORY_NAME_DUPLICATE.getCode());
        assertThatThrownBy(() -> categoryService.createCategory(request().setCode(old.getCode())))
                .isInstanceOf(ServiceException.class)
                .extracting("code")
                .isEqualTo(CATEGORY_CODE_DUPLICATE.getCode());
    }

    @Test
    void testGetCategoryPage() {
        var match = row("match").setName(prefix + "_match").setCode(prefix + "_code");
        categoryMapper.insert(match);
        categoryMapper.insert(row("name").setName(prefix + "_other").setCode(match.getCode()));
        categoryMapper.insert(row("code").setName(match.getName()).setCode(prefix + "_other"));
        categoryMapper.insert(
                row("status")
                        .setName(match.getName())
                        .setCode(match.getCode())
                        .setStatus(CommonStatusEnum.DISABLE.getStatus()));
        var otherDate = row("date").setName(match.getName()).setCode(match.getCode());
        otherDate.setCreateTime(LocalDateTime.of(2024, 2, 2, 0, 0));
        categoryMapper.insert(otherDate);
        var query = new BpmCategoryPageReqVO();
        query.setName(prefix + "_mat");
        query.setCode(prefix + "_cod");
        query.setStatus(CommonStatusEnum.ENABLE.getStatus());
        query.setCreateTime(
                new LocalDateTime[] {
                    LocalDateTime.of(2023, 2, 1, 0, 0), LocalDateTime.of(2023, 2, 28, 0, 0)
                });
        var page = categoryService.getCategoryPage(query);
        assertThat(page.getTotal()).isEqualTo(1);
        assertThat(page.getList()).extracting(BpmCategoryDO::getId).containsExactly(match.getId());
    }

    private BpmCategorySaveReqVO request() {
        return new BpmCategorySaveReqVO()
                .setId(nextId())
                .setName(prefix + "_request")
                .setCode(prefix + "_request")
                .setDescription("测试分类")
                .setStatus(CommonStatusEnum.ENABLE.getStatus())
                .setSort(1);
    }

    private BpmCategoryDO row(String suffix) {
        var value =
                new BpmCategoryDO()
                        .setId(nextId())
                        .setName(prefix + "_" + suffix)
                        .setCode(prefix + "_" + suffix)
                        .setDescription("测试分类")
                        .setStatus(CommonStatusEnum.ENABLE.getStatus())
                        .setSort(1);
        value.setCreateTime(LocalDateTime.of(2023, 2, 2, 0, 0));
        return value;
    }
}

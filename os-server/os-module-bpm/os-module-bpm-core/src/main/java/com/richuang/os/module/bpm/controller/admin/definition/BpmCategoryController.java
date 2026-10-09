package com.richuang.os.module.bpm.controller.admin.definition;

import com.richuang.os.framework.common.enums.CommonStatusEnum;
import com.richuang.os.framework.common.pojo.PageResult;
import com.richuang.os.framework.common.pojo.Result;
import com.richuang.os.framework.common.util.object.BeanUtils;
import com.richuang.os.module.bpm.controller.admin.definition.vo.category.BpmCategoryPageReqVO;
import com.richuang.os.module.bpm.controller.admin.definition.vo.category.BpmCategoryRespVO;
import com.richuang.os.module.bpm.controller.admin.definition.vo.category.BpmCategorySaveReqVO;
import com.richuang.os.module.bpm.dal.dataobject.definition.BpmCategoryDO;
import com.richuang.os.module.bpm.service.definition.BpmCategoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.Comparator;
import java.util.List;

import static com.richuang.os.framework.common.pojo.Result.success;
import static com.richuang.os.framework.common.util.collection.CollectionUtils.convertList;

@Tag(name = "管理后台 - BPM 流程分类")
@RestController
@RequestMapping("/bpm/category")
@Validated
public class BpmCategoryController {

    @Resource
    private BpmCategoryService categoryService;

    @PostMapping("/create")
    @Operation(summary = "创建流程分类")
    @PreAuthorize("@ss.hasPermission('bpm:category:create')")
    public Result<Long> createCategory(@Valid @RequestBody BpmCategorySaveReqVO createReqVO) {
        return success(categoryService.createCategory(createReqVO));
    }

    @PutMapping("/update")
    @Operation(summary = "更新流程分类")
    @PreAuthorize("@ss.hasPermission('bpm:category:update')")
    public Result<Boolean> updateCategory(@Valid @RequestBody BpmCategorySaveReqVO updateReqVO) {
        categoryService.updateCategory(updateReqVO);
        return success(true);
    }

    @PutMapping("/update-sort-batch")
    @Operation(summary = "批量更新流程分类的排序")
    @Parameter(name = "ids", description = "分类编号列表", required = true, example = "1,2,3")
    @PreAuthorize("@ss.hasPermission('bpm:category:update')")
    public Result<Boolean> updateCategorySortBatch(@RequestParam("ids") List<Long> ids) {
        categoryService.updateCategorySortBatch(ids);
        return success(true);
    }

    @DeleteMapping("/delete")
    @Operation(summary = "删除流程分类")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('bpm:category:delete')")
    public Result<Boolean> deleteCategory(@RequestParam("id") Long id) {
        categoryService.deleteCategory(id);
        return success(true);
    }

    @GetMapping("/get")
    @Operation(summary = "获得流程分类")
    @Parameter(name = "id", description = "编号", required = true, example = "1024")
    @PreAuthorize("@ss.hasPermission('bpm:category:query')")
    public Result<BpmCategoryRespVO> getCategory(@RequestParam("id") Long id) {
        BpmCategoryDO category = categoryService.getCategory(id);
        return success(BeanUtils.toBean(category, BpmCategoryRespVO.class));
    }

    @GetMapping("/page")
    @Operation(summary = "获得流程分类分页")
    @PreAuthorize("@ss.hasPermission('bpm:category:query')")
    public Result<PageResult<BpmCategoryRespVO>> getCategoryPage(@Valid BpmCategoryPageReqVO pageReqVO) {
        PageResult<BpmCategoryDO> pageResult = categoryService.getCategoryPage(pageReqVO);
        return success(BeanUtils.toBean(pageResult, BpmCategoryRespVO.class));
    }

    @GetMapping("/simple-list")
    @Operation(summary = "获取流程分类的精简信息列表", description = "只包含被开启的分类，主要用于前端的下拉选项")
    public Result<List<BpmCategoryRespVO>> getCategorySimpleList() {
        List<BpmCategoryDO> list = categoryService.getCategoryListByStatus(CommonStatusEnum.ENABLE.getStatus());
        list.sort(Comparator.comparingInt(BpmCategoryDO::getSort));
        return success(convertList(list, category -> new BpmCategoryRespVO().setId(category.getId())
                .setName(category.getName()).setCode(category.getCode())));
    }

}
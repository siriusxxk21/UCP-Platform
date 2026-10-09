package com.lingan.ucp.module.bpm.controller.admin.definition;

import com.lingan.ucp.framework.common.pojo.PageResult;
import com.lingan.ucp.framework.common.pojo.Result;
import com.lingan.ucp.framework.common.util.object.BeanUtils;
import com.lingan.ucp.module.bpm.controller.admin.definition.vo.expression.BpmProcessExpressionPageReqVO;
import com.lingan.ucp.module.bpm.controller.admin.definition.vo.expression.BpmProcessExpressionRespVO;
import com.lingan.ucp.module.bpm.controller.admin.definition.vo.expression.BpmProcessExpressionSaveReqVO;
import com.lingan.ucp.module.bpm.dal.dataobject.definition.BpmProcessExpressionDO;
import com.lingan.ucp.module.bpm.service.definition.BpmProcessExpressionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import static com.lingan.ucp.framework.common.pojo.Result.success;

@Tag(name = "管理后台 - BPM 流程表达式")
@RestController
@RequestMapping("/bpm/process-expression")
@Validated
public class BpmProcessExpressionController {

    @Resource
    private BpmProcessExpressionService processExpressionService;

    @PostMapping("/create")
    @Operation(summary = "创建流程表达式")
    @PreAuthorize("@ss.hasPermission('bpm:process-expression:create')")
    public Result<Long> createProcessExpression(@Valid @RequestBody BpmProcessExpressionSaveReqVO createReqVO) {
        return success(processExpressionService.createProcessExpression(createReqVO));
    }

    @PutMapping("/update")
    @Operation(summary = "更新流程表达式")
    @PreAuthorize("@ss.hasPermission('bpm:process-expression:update')")
    public Result<Boolean> updateProcessExpression(@Valid @RequestBody BpmProcessExpressionSaveReqVO updateReqVO) {
        processExpressionService.updateProcessExpression(updateReqVO);
        return success(true);
    }

    @DeleteMapping("/delete")
    @Operation(summary = "删除流程表达式")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('bpm:process-expression:delete')")
    public Result<Boolean> deleteProcessExpression(@RequestParam("id") Long id) {
        processExpressionService.deleteProcessExpression(id);
        return success(true);
    }

    @GetMapping("/get")
    @Operation(summary = "获得流程表达式")
    @Parameter(name = "id", description = "编号", required = true, example = "1024")
    @PreAuthorize("@ss.hasPermission('bpm:process-expression:query')")
    public Result<BpmProcessExpressionRespVO> getProcessExpression(@RequestParam("id") Long id) {
        BpmProcessExpressionDO processExpression = processExpressionService.getProcessExpression(id);
        return success(BeanUtils.toBean(processExpression, BpmProcessExpressionRespVO.class));
    }

    @GetMapping("/page")
    @Operation(summary = "获得流程表达式分页")
    @PreAuthorize("@ss.hasPermission('bpm:process-expression:query')")
    public Result<PageResult<BpmProcessExpressionRespVO>> getProcessExpressionPage(
            @Valid BpmProcessExpressionPageReqVO pageReqVO) {
        PageResult<BpmProcessExpressionDO> pageResult = processExpressionService.getProcessExpressionPage(pageReqVO);
        return success(BeanUtils.toBean(pageResult, BpmProcessExpressionRespVO.class));
    }

}
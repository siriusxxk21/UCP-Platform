package com.richuang.os.module.bpm.controller.admin.definition;

import com.richuang.os.framework.common.pojo.PageResult;
import com.richuang.os.framework.common.pojo.Result;
import com.richuang.os.framework.common.util.object.BeanUtils;
import com.richuang.os.module.bpm.controller.admin.definition.vo.listener.BpmProcessListenerPageReqVO;
import com.richuang.os.module.bpm.controller.admin.definition.vo.listener.BpmProcessListenerRespVO;
import com.richuang.os.module.bpm.controller.admin.definition.vo.listener.BpmProcessListenerSaveReqVO;
import com.richuang.os.module.bpm.dal.dataobject.definition.BpmProcessListenerDO;
import com.richuang.os.module.bpm.service.definition.BpmProcessListenerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import static com.richuang.os.framework.common.pojo.Result.success;

@Tag(name = "管理后台 - BPM 流程监听器")
@RestController
@RequestMapping("/bpm/process-listener")
@Validated
public class BpmProcessListenerController {

    @Resource
    private BpmProcessListenerService processListenerService;

    @PostMapping("/create")
    @Operation(summary = "创建流程监听器")
    @PreAuthorize("@ss.hasPermission('bpm:process-listener:create')")
    public Result<Long> createProcessListener(@Valid @RequestBody BpmProcessListenerSaveReqVO createReqVO) {
        return success(processListenerService.createProcessListener(createReqVO));
    }

    @PutMapping("/update")
    @Operation(summary = "更新流程监听器")
    @PreAuthorize("@ss.hasPermission('bpm:process-listener:update')")
    public Result<Boolean> updateProcessListener(@Valid @RequestBody BpmProcessListenerSaveReqVO updateReqVO) {
        processListenerService.updateProcessListener(updateReqVO);
        return success(true);
    }

    @DeleteMapping("/delete")
    @Operation(summary = "删除流程监听器")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('bpm:process-listener:delete')")
    public Result<Boolean> deleteProcessListener(@RequestParam("id") Long id) {
        processListenerService.deleteProcessListener(id);
        return success(true);
    }

    @GetMapping("/get")
    @Operation(summary = "获得流程监听器")
    @Parameter(name = "id", description = "编号", required = true, example = "1024")
    @PreAuthorize("@ss.hasPermission('bpm:process-listener:query')")
    public Result<BpmProcessListenerRespVO> getProcessListener(@RequestParam("id") Long id) {
        BpmProcessListenerDO processListener = processListenerService.getProcessListener(id);
        return success(BeanUtils.toBean(processListener, BpmProcessListenerRespVO.class));
    }

    @GetMapping("/page")
    @Operation(summary = "获得流程监听器分页")
    @PreAuthorize("@ss.hasPermission('bpm:process-listener:query')")
    public Result<PageResult<BpmProcessListenerRespVO>> getProcessListenerPage(
            @Valid BpmProcessListenerPageReqVO pageReqVO) {
        PageResult<BpmProcessListenerDO> pageResult = processListenerService.getProcessListenerPage(pageReqVO);
        return success(BeanUtils.toBean(pageResult, BpmProcessListenerRespVO.class));
    }

}
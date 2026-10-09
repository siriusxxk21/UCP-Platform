package com.lingan.ucp.nocode.workflow.service.material;

import com.lingan.ucp.nocode.api.workflow.FlowMaterials;

/** 审批材料与本人草稿读取分离，入口不产生任何草稿或任务绑定。 */
public interface FlowMaterialService {
    FlowMaterials.Page list(FlowMaterials.Query command, long actor);

    FlowMaterials.Detail detail(FlowMaterials.Get command, long actor);
}

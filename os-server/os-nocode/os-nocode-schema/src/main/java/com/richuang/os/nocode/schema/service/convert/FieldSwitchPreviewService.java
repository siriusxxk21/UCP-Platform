package com.richuang.os.nocode.schema.service.convert;

import com.richuang.os.nocode.api.FieldConversions;
import com.richuang.os.nocode.api.FieldSwitchPreview;

/** 仅预览指定稳定字段切换的物理数据影响，不保存设计或变更业务数据。 */
public interface FieldSwitchPreviewService {
    FieldSwitchPreview.Result preview(FieldSwitchPreview.Request request);

    /** 分页读取已发布物理列中的非空原值；切换方案尚未保存时也可使用。 */
    FieldConversions.Page rows(
            String objectId, String detailId, String fieldId, int pageNo, int pageSize);

    /** 按完整切换意图展示旧值、整列决定和可确定的新值。 */
    FieldConversions.Page rows(FieldSwitchPreview.RowsRequest request);
}

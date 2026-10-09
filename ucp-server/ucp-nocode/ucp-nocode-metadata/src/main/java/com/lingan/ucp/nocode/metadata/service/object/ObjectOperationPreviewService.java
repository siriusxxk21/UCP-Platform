package com.lingan.ucp.nocode.metadata.service.object;

import com.lingan.ucp.nocode.api.ObjectOperationPreview;

/** 为对象及字段治理动作提供不写入草稿或业务数据的预检。 */
public interface ObjectOperationPreviewService {
    ObjectOperationPreview.Result preview(ObjectOperationPreview.Request request);
}

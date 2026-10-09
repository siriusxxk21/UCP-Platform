package com.richuang.os.nocode.metadata.service.object;

import com.richuang.os.nocode.api.ObjectOperationPreview;

/** 为对象及字段治理动作提供不写入草稿或业务数据的预检。 */
public interface ObjectOperationPreviewService {
    ObjectOperationPreview.Result preview(ObjectOperationPreview.Request request);
}

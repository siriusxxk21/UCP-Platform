package com.richuang.os.nocode.runtime.service.taskcenter;

import com.richuang.os.framework.common.pojo.PageResult;
import com.richuang.os.nocode.api.BusinessFiles;
import com.richuang.os.nocode.api.TaskBusinessFiles;

import java.io.InputStream;

/** 固定任务表单内的附件能力，不提供应用目录或记录文件夹浏览。 */
public interface TaskBusinessFileService {
    PageResult<BusinessFiles.File> files(TaskBusinessFiles.Files request, long actor);

    BusinessFiles.Content content(TaskBusinessFiles.Content request, long actor);

    BusinessFiles.Uploaded upload(
            TaskBusinessFiles.Upload request, long actor, InputStream content);

    BusinessFiles.TemporaryContent temporaryContent(
            TaskBusinessFiles.Temporary request, long actor);

    boolean renew(TaskBusinessFiles.Renew request, long actor);

    InputStream contentStream(BusinessFiles.Content content, long offset);

    InputStream temporaryStream(BusinessFiles.TemporaryContent content, long offset);
}

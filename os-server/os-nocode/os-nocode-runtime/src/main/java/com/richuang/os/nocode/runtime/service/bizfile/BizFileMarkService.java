package com.richuang.os.nocode.runtime.service.bizfile;

import static com.richuang.os.nocode.api.NocodeErrorCodes.invalid;

import com.richuang.os.framework.common.exception.ServiceException;
import com.richuang.os.framework.common.pojo.PageResult;
import com.richuang.os.nocode.api.BusinessFiles;
import com.richuang.os.nocode.enums.BusinessFileMarkTypeEnum;
import com.richuang.os.nocode.runtime.dal.mapper.BizFileMarkMapper;

import jakarta.annotation.Resource;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 业务文件收藏与最近访问 Service
 *
 * <p>标记按用户独立保存，读写都不构成任何授权依据：登记前先经业务浏览服务重验可见绑定， 列表也由业务浏览链重新过滤后返回。网盘侧收藏按空间成员身份过滤，受管业务节点不在其授权链内，
 * 因此业务文件标记独立保存于无代码侧，不落网盘标记表。
 */
@Service
public class BizFileMarkService {

    /** 标记列表条数上限，与浏览分页上限一致；先取标记再按授权链重取可见文件 */
    private static final int MAX_MARK_LIMIT = 100;

    @Resource private BizFileMarkMapper marks;
    @Resource private BizFileBrowseService browse;

    /** 收藏切换：位置身份与内容读取一致，先重验可见绑定再写本人标记；标记不改变文件对任何人的可见性 */
    public Boolean favorite(BusinessFiles.FavoriteQuery request, long actor) {
        if (request == null) throw invalid("业务文件收藏请求不能为空");
        if (request.favorite() == null) throw invalid("收藏状态不能为空");
        browse.requireBinding(contentLocation(request), actor);
        if (Boolean.TRUE.equals(request.favorite())) {
            marks.insertFavorite(
                    actor, request.objectId(), request.entryId(), Long.toString(actor));
            return Boolean.TRUE;
        }
        marks.deleteFavorite(actor, request.entryId(), Long.toString(actor));
        return Boolean.FALSE;
    }

    /** 最近访问登记：预览/下载时由业务文件入口调用；位置失效或不可见时静默跳过，不影响读取主流程 */
    public void access(BusinessFiles.ContentQuery request, long actor) {
        if (request == null) return;
        try {
            browse.requireBinding(request, actor);
        } catch (ServiceException inaccessible) {
            return;
        }
        marks.upsertRecent(actor, request.objectId(), request.entryId(), Long.toString(actor));
    }

    /** 收藏/最近访问列表：本人标记按时间倒序取前 100，再经入口浏览链重取当前可见文件并按标记顺序返回 */
    public PageResult<BusinessFiles.File> markedFiles(BusinessFiles.MarkQuery request, long actor) {
        if (request == null) throw invalid("业务文件标记查询不能为空");
        BizFileBrowseService.requireObjectId(request.objectId());
        BusinessFileMarkTypeEnum markType = BusinessFileMarkTypeEnum.fromCode(request.markType());
        List<Long> entryIds =
                marks.selectEntryIds(actor, request.objectId(), markType.getCode(), MAX_MARK_LIMIT);
        if (entryIds.isEmpty()) return new PageResult<>(List.of(), 0L);
        List<BusinessFiles.File> visible =
                browse.filesByEntries(request.applicationId(), request.objectId(), entryIds, actor);
        Map<Long, BusinessFiles.File> byEntry = new HashMap<>();
        for (BusinessFiles.File file : visible) {
            byEntry.putIfAbsent(file.entryId(), file);
        }
        List<BusinessFiles.File> items = new ArrayList<>();
        for (Long entryId : entryIds) {
            BusinessFiles.File file = byEntry.get(entryId);
            if (file != null) items.add(file);
        }
        return new PageResult<>(items, (long) items.size());
    }

    private static BusinessFiles.ContentQuery contentLocation(BusinessFiles.FavoriteQuery request) {
        return new BusinessFiles.ContentQuery(
                request.applicationId(),
                request.objectId(),
                request.recordId(),
                request.detailId(),
                request.rowId(),
                request.fieldId(),
                request.entryId());
    }
}

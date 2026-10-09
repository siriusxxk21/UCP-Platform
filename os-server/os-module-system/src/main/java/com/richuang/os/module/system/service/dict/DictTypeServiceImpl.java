package com.richuang.os.module.system.service.dict;

import static com.richuang.os.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.richuang.os.framework.common.exception.util.ServiceExceptionUtil.invalidParamException;
import static com.richuang.os.module.system.enums.ErrorCodeConstants.*;

import cn.hutool.core.util.StrUtil;

import com.google.common.annotations.VisibleForTesting;
import com.richuang.os.framework.common.pojo.PageResult;
import com.richuang.os.framework.common.util.date.LocalDateTimeUtils;
import com.richuang.os.framework.common.util.object.BeanUtils;
import com.richuang.os.framework.security.core.util.SecurityFrameworkUtils;
import com.richuang.os.module.system.controller.admin.dict.vo.type.DictTypePageReqVO;
import com.richuang.os.module.system.controller.admin.dict.vo.type.DictTypeSaveReqVO;
import com.richuang.os.module.system.dal.dataobject.dict.DictTypeDO;
import com.richuang.os.module.system.dal.mysql.dict.DictTypeMapper;

import jakarta.annotation.Resource;

import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 字典类型 Service 实现类
 *
 * @author os
 */
@Service
public class DictTypeServiceImpl implements DictTypeService {

    @Resource @Lazy private DictDataService dictDataService;

    @Resource private DictTypeMapper dictTypeMapper;

    @Override
    public PageResult<DictTypeDO> getDictTypePage(DictTypePageReqVO pageReqVO) {
        return dictTypeMapper.selectPage(pageReqVO);
    }

    @Override
    public DictTypeDO getDictType(Long id) {
        return dictTypeMapper.selectById(id);
    }

    @Override
    public DictTypeDO getDictType(String type) {
        return dictTypeMapper.selectByType(type);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createDictType(DictTypeSaveReqVO createReqVO) {
        dictTypeMapper.lockDictionaryWrites();
        // 校验字典类型的名字的唯一性
        validateDictTypeNameUnique(null, createReqVO.getName());
        // 校验字典类型的类型的唯一性
        validateDictTypeUnique(null, createReqVO.getType());

        // 插入字典类型
        DictTypeDO dictType = BeanUtils.toBean(createReqVO, DictTypeDO.class);
        dictType.setDeletedTime(LocalDateTimeUtils.EMPTY); // 唯一索引，避免 null 值
        dictTypeMapper.insert(dictType);
        return dictType.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateDictType(DictTypeSaveReqVO updateReqVO) {
        dictTypeMapper.lockDictionaryWrites();
        // 校验自己存在
        DictTypeDO existing = validateDictTypeExists(updateReqVO.getId());
        // 编码被数据项和业务配置引用，禁止仅改父表导致引用失联。
        if (!existing.getType().equals(updateReqVO.getType())) {
            throw exception(DICT_TYPE_CODE_IMMUTABLE);
        }
        // 校验字典类型的名字的唯一性
        validateDictTypeNameUnique(updateReqVO.getId(), updateReqVO.getName());
        // 校验字典类型的类型的唯一性
        validateDictTypeUnique(updateReqVO.getId(), updateReqVO.getType());

        // 更新字典类型
        DictTypeDO updateObj = BeanUtils.toBean(updateReqVO, DictTypeDO.class);
        dictTypeMapper.updateById(updateObj);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteDictType(Long id) {
        dictTypeMapper.lockDictionaryWrites();
        // 校验是否存在
        DictTypeDO dictType = validateDictTypeExists(id);
        // 校验是否有字典数据
        if (dictDataService.getDictDataCountByDictType(dictType.getType()) > 0) {
            throw exception(DICT_TYPE_HAS_CHILDREN);
        }
        // 删除字典类型
        dictTypeMapper.updateToDelete(
                id, LocalDateTime.now(), String.valueOf(SecurityFrameworkUtils.getLoginUserId()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteDictTypeList(List<Long> ids) {
        dictTypeMapper.lockDictionaryWrites();
        // 1. 校验是否有字典数据
        if (ids == null || ids.isEmpty()) {
            throw invalidParamException("请选择要删除的字典类型");
        }
        List<DictTypeDO> dictTypes =
                ids.stream().distinct().map(this::validateDictTypeExists).toList();
        dictTypes.forEach(
                dictType -> {
                    if (dictDataService.getDictDataCountByDictType(dictType.getType()) > 0) {
                        throw exception(DICT_TYPE_HAS_CHILDREN);
                    }
                });

        // 2. 批量删除字典类型
        LocalDateTime now = LocalDateTime.now();
        dictTypes.forEach(
                type ->
                        dictTypeMapper.updateToDelete(
                                type.getId(),
                                now,
                                String.valueOf(SecurityFrameworkUtils.getLoginUserId())));
    }

    @Override
    public List<DictTypeDO> getDictTypeList() {
        return dictTypeMapper.selectList();
    }

    @VisibleForTesting
    void validateDictTypeNameUnique(Long id, String name) {
        DictTypeDO dictType = dictTypeMapper.selectByName(name);
        if (dictType == null) {
            return;
        }
        // 如果 id 为空，说明不用比较是否为相同 id 的字典类型
        if (id == null) {
            throw exception(DICT_TYPE_NAME_DUPLICATE);
        }
        if (!dictType.getId().equals(id)) {
            throw exception(DICT_TYPE_NAME_DUPLICATE);
        }
    }

    @VisibleForTesting
    void validateDictTypeUnique(Long id, String type) {
        if (StrUtil.isEmpty(type)) {
            return;
        }
        DictTypeDO dictType = dictTypeMapper.selectByType(type);
        if (dictType == null) {
            return;
        }
        // 如果 id 为空，说明不用比较是否为相同 id 的字典类型
        if (id == null) {
            throw exception(DICT_TYPE_TYPE_DUPLICATE);
        }
        if (!dictType.getId().equals(id)) {
            throw exception(DICT_TYPE_TYPE_DUPLICATE);
        }
    }

    @VisibleForTesting
    DictTypeDO validateDictTypeExists(Long id) {
        if (id == null) {
            throw invalidParamException("字典类型编号不能为空");
        }
        DictTypeDO dictType = dictTypeMapper.selectById(id);
        if (dictType == null) {
            throw exception(DICT_TYPE_NOT_EXISTS);
        }
        return dictType;
    }
}

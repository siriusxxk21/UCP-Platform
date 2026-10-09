package com.lingan.ucp.module.system.service.dict;

import static com.lingan.ucp.module.system.enums.ErrorCodeConstants.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.lingan.ucp.framework.common.exception.ServiceException;
import com.lingan.ucp.module.system.controller.admin.dict.vo.data.DictDataSaveReqVO;
import com.lingan.ucp.module.system.controller.admin.dict.vo.type.DictTypeSaveReqVO;
import com.lingan.ucp.module.system.dal.dataobject.dict.DictDataDO;
import com.lingan.ucp.module.system.dal.dataobject.dict.DictTypeDO;
import com.lingan.ucp.module.system.dal.mysql.dict.DictDataMapper;
import com.lingan.ucp.module.system.dal.mysql.dict.DictTypeMapper;

import jakarta.validation.Validation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;

/** 覆盖字典维护中会破坏引用、绕过校验或导致部分删除的边界。 */
class DictManagementTest {
    private DictTypeMapper typeMapper;
    private DictDataMapper dataMapper;
    private DictTypeServiceImpl types;
    private DictDataServiceImpl data;

    @BeforeEach
    void setUp() {
        typeMapper = mock(DictTypeMapper.class);
        dataMapper = mock(DictDataMapper.class);
        types = new DictTypeServiceImpl();
        data = new DictDataServiceImpl();
        ReflectionTestUtils.setField(types, "dictTypeMapper", typeMapper);
        ReflectionTestUtils.setField(types, "dictDataService", data);
        ReflectionTestUtils.setField(data, "dictTypeService", types);
        ReflectionTestUtils.setField(data, "dictTypeMapper", typeMapper);
        ReflectionTestUtils.setField(data, "dictDataMapper", dataMapper);
    }

    @Test
    void rejectsTypeCodeChangeWithoutWriting() {
        when(typeMapper.selectById(1L)).thenReturn(type(1L, "old"));
        DictTypeSaveReqVO request = new DictTypeSaveReqVO();
        request.setId(1L);
        request.setType("new");
        assertEquals(
                DICT_TYPE_CODE_IMMUTABLE.getCode(),
                assertThrows(ServiceException.class, () -> types.updateDictType(request))
                        .getCode());
        verify(typeMapper, never()).updateById(any(DictTypeDO.class));
    }

    @Test
    void batchTypeDeleteValidatesEveryParentBeforeWriting() {
        when(typeMapper.selectById(1L)).thenReturn(type(1L, "empty"));
        when(typeMapper.selectById(2L)).thenReturn(type(2L, "used"));
        when(dataMapper.selectCountByDictType("used")).thenReturn(1L);
        assertEquals(
                DICT_TYPE_HAS_CHILDREN.getCode(),
                assertThrows(
                                ServiceException.class,
                                () -> types.deleteDictTypeList(List.of(1L, 2L)))
                        .getCode());
        verify(typeMapper, never()).updateToDelete(anyLong(), any(), anyString());
    }

    @Test
    void batchDataDeleteDoesNotPartiallyDeleteWhenAnIdIsMissing() {
        when(dataMapper.selectById(1L)).thenReturn(item(1L, 0));
        assertThrows(ServiceException.class, () -> data.deleteDictDataList(List.of(1L, 2L)));
        verify(dataMapper, never()).deleteByIds(anyCollection());
    }

    @Test
    void updateRequiresIds() {
        assertThrows(ServiceException.class, () -> types.updateDictType(new DictTypeSaveReqVO()));
        assertThrows(ServiceException.class, () -> data.updateDictData(new DictDataSaveReqVO()));
    }

    @Test
    void rejectsEmptyBatchDelete() {
        assertThrows(ServiceException.class, () -> types.deleteDictTypeList(List.of()));
        assertThrows(ServiceException.class, () -> data.deleteDictDataList(List.of()));
    }

    @Test
    void preventsDataCreationForDisabledType() {
        DictTypeDO type = type(1L, "status");
        type.setStatus(1);
        when(typeMapper.selectByType("status")).thenReturn(type);
        assertEquals(
                DICT_TYPE_NOT_ENABLE.getCode(),
                assertThrows(ServiceException.class, () -> data.createDictData(request()))
                        .getCode());
        verify(dataMapper, never()).insert(any(DictDataDO.class));
    }

    @Test
    void duplicateValuesAreCheckedAfterAcquiringWriteLock() {
        when(typeMapper.selectByType("status")).thenReturn(type(1L, "status"));
        when(dataMapper.selectByDictTypeAndValue("status", "0")).thenReturn(item(1L, 0));
        assertEquals(
                DICT_DATA_VALUE_DUPLICATE.getCode(),
                assertThrows(ServiceException.class, () -> data.createDictData(request()))
                        .getCode());
        var order = inOrder(typeMapper, dataMapper);
        order.verify(typeMapper).lockDictionaryWrites();
        order.verify(typeMapper).selectByType("status");
        order.verify(dataMapper).selectByDictTypeAndValue("status", "0");
        verify(dataMapper, never()).insert(any(DictDataDO.class));
    }

    @Test
    void simpleListUsesStableAscendingOrder() {
        when(dataMapper.selectListByStatusAndDictType(0, "status"))
                .thenReturn(new ArrayList<>(List.of(item(3L, 2), item(2L, 1), item(1L, 1))));
        assertEquals(
                List.of(1L, 2L, 3L),
                data.getDictDataList(0, "status").stream().map(DictDataDO::getId).toList());
    }

    @Test
    void validatesDatabaseLengthsBlankCodeAndStatus() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            DictTypeSaveReqVO type = new DictTypeSaveReqVO();
            type.setName("类型");
            type.setType("   ");
            type.setStatus(7);
            type.setRemark("x".repeat(501));
            assertEquals(3, validator.validate(type).size());
            DictDataSaveReqVO item = request();
            item.setSort(-1);
            item.setCssClass("x".repeat(101));
            item.setColorType("x".repeat(101));
            item.setRemark("x".repeat(501));
            assertEquals(4, validator.validate(item).size());
        }
    }

    private DictTypeDO type(Long id, String code) {
        return DictTypeDO.builder().id(id).type(code).name(code).status(0).build();
    }

    private DictDataDO item(Long id, int sort) {
        DictDataDO item = new DictDataDO();
        item.setId(id);
        item.setDictType("status");
        item.setSort(sort);
        return item;
    }

    private DictDataSaveReqVO request() {
        DictDataSaveReqVO request = new DictDataSaveReqVO();
        request.setDictType("status");
        request.setLabel("启用");
        request.setValue("0");
        request.setSort(0);
        request.setStatus(0);
        return request;
    }
}

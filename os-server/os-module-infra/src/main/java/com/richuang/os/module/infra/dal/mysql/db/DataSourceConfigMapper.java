package com.richuang.os.module.infra.dal.mysql.db;

import com.richuang.os.framework.mybatis.core.mapper.BaseMapperX;
import com.richuang.os.module.infra.dal.dataobject.db.DataSourceConfigDO;
import org.apache.ibatis.annotations.Mapper;

/**
 * 数据源配置 Mapper
 *
 * @author os
 */
@Mapper
public interface DataSourceConfigMapper extends BaseMapperX<DataSourceConfigDO> {
}

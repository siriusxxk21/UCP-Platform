package com.richuang.os.nocode.metadata.dal.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;

import org.apache.ibatis.annotations.*;

import java.util.List;

/** 选择来源迁移专用 SQL。标识符由对象版本编译，映射和值通过 MyBatis 绑定。 */
@Mapper
public interface SelectionMigrationMapper {
    /**
     * 选择来源变更的内部执行参数，不从 HTTP 直接接收物理标识符。
     *
     * @param type 已验证的目标 PostgreSQL 类型，迁移时再次通过底座类型白名单
     * @param oldMultiple 旧列是否按 JSON 数组读取
     * @param newMultiple 映射后是否保留多个目标值；单值仅取已验证的第一个目标
     * @param required 目标列是否必填，同时参与映射冲突预检
     * @param mapping 旧值到目标值数组的 JSON 映射，通过 MyBatis 绑定
     * @param audit 是否同步维护已确认存在的 BaseDO 审计字段
     */
    record Statement(
            String schema,
            String table,
            String column,
            String type,
            boolean oldMultiple,
            boolean newMultiple,
            boolean required,
            String mapping,
            String actor,
            boolean audit) {}

    /** 读取最多 1001 个去重旧值，用于迁移规模和映射完整性检查。 */
    List<String> values(Statement statement);

    /** 统计映射后违反必填或目标多选数量约束的行。 */
    long conflicts(Statement statement);

    /** 在发布事务中先转为文本承接中间值；调用顺序由迁移服务维护。 */
    @InterceptorIgnore(dataPermission = "true", tenantLine = "true")
    void toText(Statement statement);

    /** 按原有位置顺序去重和应用映射，仅处理非空旧值。 */
    @InterceptorIgnore(dataPermission = "true", tenantLine = "true")
    void mapValues(Statement statement);

    /** 映射完成后恢复目标类型及必填约束。 */
    @InterceptorIgnore(dataPermission = "true", tenantLine = "true")
    void toTarget(Statement statement);

    /** 选择迁移延续既有名称长度规则；目标类型继续复用底座白名单。 */
    final class Identifiers {
        private Identifiers() {}

        public static String quote(String value) {
            if (value == null || value.length() > 63 || value.indexOf('\0') >= 0)
                throw new IllegalArgumentException("无效标识符");
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
    }
}

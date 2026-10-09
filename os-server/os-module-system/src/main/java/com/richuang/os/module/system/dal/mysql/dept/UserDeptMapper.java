package com.richuang.os.module.system.dal.mysql.dept;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.richuang.os.framework.mybatis.core.mapper.BaseMapperX;
import com.richuang.os.module.system.controller.admin.dept.vo.dept.DeptUserRespVO;
import com.richuang.os.module.system.dal.dataobject.dept.UserDeptDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 用户部门关联数据访问接口。
 * 负责关联关系写入，以及包含用户展示字段的部门成员查询。
 */
@Mapper
public interface UserDeptMapper extends BaseMapperX<UserDeptDO> {

    @Select("<script>"
            + "SELECT ud.id, ud.user_id, u.username, u.nickname, u.mobile AS phone, u.email, "
            + "u.status AS user_status, ud.post, ud.is_main, ud.create_time "
            + "FROM system_user_dept ud INNER JOIN system_users u ON u.id = ud.user_id "
            + "WHERE ud.dept_id = #{deptId} AND ud.deleted = 0 AND u.deleted = 0 "
            + "<if test='username != null and username != &quot;&quot;'>"
            + "AND (u.username LIKE CONCAT('%', CONCAT(#{username}, '%')) "
            + "OR u.nickname LIKE CONCAT('%', CONCAT(#{username}, '%')) "
            + "OR LOWER(u.nickname_pinyin) LIKE CONCAT('%', CONCAT(LOWER(#{username}), '%')) "
            + "OR LOWER(u.nickname_pinyin_initial) LIKE CONCAT('%', CONCAT(LOWER(#{username}), '%'))) "
            + "</if> ORDER BY ud.is_main DESC, ud.create_time ASC"
            + "</script>")
    Page<DeptUserRespVO> selectDeptUsersPage(Page<DeptUserRespVO> page,
                                             @Param("deptId") Long deptId,
                                             @Param("username") String username);

    @Select("<script>"
            + "SELECT ud.id, ud.user_id, u.username, u.nickname, u.mobile AS phone, u.email, "
            + "u.status AS user_status, ud.post, ud.is_main, ud.create_time "
            + "FROM system_user_dept ud INNER JOIN system_users u ON u.id = ud.user_id "
            + "WHERE ud.dept_id = #{deptId} AND ud.deleted = 0 AND u.deleted = 0 "
            + "<if test='username != null and username != &quot;&quot;'>"
            + "AND (u.username LIKE CONCAT('%', CONCAT(#{username}, '%')) "
            + "OR u.nickname LIKE CONCAT('%', CONCAT(#{username}, '%')) "
            + "OR LOWER(u.nickname_pinyin) LIKE CONCAT('%', CONCAT(LOWER(#{username}), '%')) "
            + "OR LOWER(u.nickname_pinyin_initial) LIKE CONCAT('%', CONCAT(LOWER(#{username}), '%'))) "
            + "</if> ORDER BY ud.is_main DESC, ud.create_time ASC"
            + "</script>")
    List<DeptUserRespVO> selectDeptUsers(@Param("deptId") Long deptId,
                                         @Param("username") String username);

    @Select("<script>"
            + "SELECT u.id AS user_id, u.username, u.nickname, u.mobile AS phone, u.email, "
            + "u.status AS user_status, 0 AS is_main, u.create_time "
            + "FROM system_users u WHERE u.deleted = 0 "
            + "<if test='orgId != null'>AND (u.org_id IS NULL OR u.org_id = #{orgId}) </if>"
            + "AND NOT EXISTS (SELECT 1 FROM system_user_dept ud "
            + "WHERE ud.user_id = u.id AND ud.dept_id = #{deptId} AND ud.deleted = 0) "
            + "<if test='username != null and username != &quot;&quot;'>"
            + "AND (u.username LIKE CONCAT('%', CONCAT(#{username}, '%')) "
            + "OR u.nickname LIKE CONCAT('%', CONCAT(#{username}, '%')) "
            + "OR LOWER(u.nickname_pinyin) LIKE CONCAT('%', CONCAT(LOWER(#{username}), '%')) "
            + "OR LOWER(u.nickname_pinyin_initial) LIKE CONCAT('%', CONCAT(LOWER(#{username}), '%'))) "
            + "</if> ORDER BY u.id DESC"
            + "</script>")
    Page<DeptUserRespVO> selectAvailableUsersPage(Page<DeptUserRespVO> page,
                                                  @Param("deptId") Long deptId,
                                                  @Param("orgId") Long orgId,
                                                  @Param("username") String username);

    @Select("<script>"
            + "SELECT u.id AS user_id, u.username, u.nickname, u.mobile AS phone, u.email, "
            + "u.status AS user_status, 0 AS is_main, u.create_time "
            + "FROM system_users u WHERE u.deleted = 0 "
            + "<if test='orgId != null'>AND (u.org_id IS NULL OR u.org_id = #{orgId}) </if>"
            + "AND NOT EXISTS (SELECT 1 FROM system_user_dept ud "
            + "WHERE ud.user_id = u.id AND ud.dept_id = #{deptId} AND ud.deleted = 0) "
            + "<if test='username != null and username != &quot;&quot;'>"
            + "AND (u.username LIKE CONCAT('%', CONCAT(#{username}, '%')) "
            + "OR u.nickname LIKE CONCAT('%', CONCAT(#{username}, '%')) "
            + "OR LOWER(u.nickname_pinyin) LIKE CONCAT('%', CONCAT(LOWER(#{username}), '%')) "
            + "OR LOWER(u.nickname_pinyin_initial) LIKE CONCAT('%', CONCAT(LOWER(#{username}), '%'))) "
            + "</if> ORDER BY u.id DESC"
            + "</script>")
    List<DeptUserRespVO> selectAvailableUsers(@Param("deptId") Long deptId,
                                              @Param("orgId") Long orgId,
                                              @Param("username") String username);

    @Select("SELECT id FROM system_users WHERE id = #{userId} AND deleted = 0 FOR UPDATE")
    Long lockUser(@Param("userId") Long userId);
}

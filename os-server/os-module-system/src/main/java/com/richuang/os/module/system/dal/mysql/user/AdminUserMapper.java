package com.richuang.os.module.system.dal.mysql.user;

import cn.hutool.core.util.StrUtil;
import cn.hutool.core.collection.CollUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.richuang.os.common.util.DynamicQueryProcessor;
import com.richuang.os.framework.common.pojo.PageResult;
import com.richuang.os.framework.mybatis.core.mapper.BaseMapperX;
import com.richuang.os.module.system.controller.admin.user.vo.user.UserPageReqVO;
import com.richuang.os.module.system.dal.dataobject.user.AdminUserDO;
import org.apache.ibatis.annotations.Mapper;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Mapper
public interface AdminUserMapper extends BaseMapperX<AdminUserDO> {

    /** 前端高级检索字段到数据库列的白名单映射。 */
    Map<String, String> DYNAMIC_CONDITION_COLUMNS = Map.of(
            "username", "username",
            "nickname", "nickname",
            "mobile", "mobile",
            "status", "status",
            "createTime", "create_time"
    );

    default AdminUserDO selectByUsername(String username) {
        return selectOne(AdminUserDO::getUsername, username);
    }

    default AdminUserDO selectByEmail(String email) {
        return selectOne(AdminUserDO::getEmail, email);
    }

    default AdminUserDO selectByMobile(String mobile) {
        return selectOne(AdminUserDO::getMobile, mobile);
    }

    default PageResult<AdminUserDO> selectPage(UserPageReqVO reqVO, Collection<Long> deptIds,
                                               Collection<Long> orgIds, Collection<Long> userIds) {
        QueryWrapper<AdminUserDO> wrapper = new QueryWrapper<AdminUserDO>()
                .like(StrUtil.isNotBlank(reqVO.getMobile()), "mobile", reqVO.getMobile())
                .eq(reqVO.getStatus() != null, "status", reqVO.getStatus())
                .between(reqVO.getCreateTime() != null && reqVO.getCreateTime().length == 2,
                        "create_time", reqVO.getCreateTime() == null ? null : reqVO.getCreateTime()[0],
                        reqVO.getCreateTime() == null ? null : reqVO.getCreateTime()[1])
                .in(CollUtil.isNotEmpty(deptIds), "dept_id", deptIds)
                .in(CollUtil.isNotEmpty(orgIds), "org_id", orgIds)
                .in(CollUtil.isNotEmpty(userIds), "id", userIds)
                .in(CollUtil.isNotEmpty(reqVO.getIds()), "id", reqVO.getIds());
        if (StrUtil.isNotBlank(reqVO.getUsername())) {
            String username = reqVO.getUsername();
            String pinyinKeyword = username.toLowerCase(Locale.ROOT);
            wrapper.and(w -> w.like("username", username)
                    .or().like("nickname", username)
                    .or().like("nickname_pinyin", pinyinKeyword)
                    .or().like("nickname_pinyin_initial", pinyinKeyword));
        }
        DynamicQueryProcessor.applyConditions(wrapper, reqVO.getConditions(), DYNAMIC_CONDITION_COLUMNS);
        // 关键词模糊搜索（username / nickname / mobile）
        if (StrUtil.isNotBlank(reqVO.getKeyword())) {
            String kw = reqVO.getKeyword();
            String pinyinKeyword = kw.toLowerCase(Locale.ROOT);
            wrapper.and(w -> w.like("username", kw)
                    .or().like("nickname", kw)
                    .or().like("mobile", kw)
                    .or().like("nickname_pinyin", pinyinKeyword)
                    .or().like("nickname_pinyin_initial", pinyinKeyword));
        }
        wrapper.orderByDesc("id");
        return selectPage(reqVO, wrapper);
    }

    default List<AdminUserDO> selectListByNickname(String nickname) {
        String pinyinKeyword = nickname.toLowerCase(Locale.ROOT);
        QueryWrapper<AdminUserDO> wrapper = new QueryWrapper<AdminUserDO>()
                .and(w -> w.like("nickname", nickname)
                        .or().like("nickname_pinyin", pinyinKeyword)
                        .or().like("nickname_pinyin_initial", pinyinKeyword));
        return selectList(wrapper);
    }

    /**
     * 查询仍未生成昵称拼音的用户，使用分页避免一次性加载全部历史数据。
     */
    default Page<AdminUserDO> selectPageMissingNicknamePinyin(Page<AdminUserDO> page) {
        QueryWrapper<AdminUserDO> wrapper = new QueryWrapper<>();
        // 仅按 NULL 判断未回填记录；空昵称的合法结果也是空字符串，不能作为未完成标记，否则任务会重复处理。
        wrapper.and(condition -> condition.isNull("nickname_pinyin")
                .or().isNull("nickname_pinyin_initial"));
        wrapper.orderByAsc("id");
        return selectPage(page, wrapper);
    }

    default List<AdminUserDO> selectListByStatus(Integer status) {
        return selectList(AdminUserDO::getStatus, status);
    }

    /**
     * 查询启用用户的简要信息。关键词由后端统一匹配账号、昵称、手机号和昵称拼音。
     */
    default List<AdminUserDO> selectListByStatusAndKeyword(Integer status, String keyword) {
        String pinyinKeyword = keyword.toLowerCase(Locale.ROOT);
        QueryWrapper<AdminUserDO> wrapper = new QueryWrapper<AdminUserDO>()
                .eq("status", status)
                .and(w -> w.like("username", keyword)
                        .or().like("nickname", keyword)
                        .or().like("mobile", keyword)
                        .or().like("nickname_pinyin", pinyinKeyword)
                        .or().like("nickname_pinyin_initial", pinyinKeyword))
                .orderByDesc("id");
        return selectList(wrapper);
    }

    default List<AdminUserDO> selectListByDeptIds(Collection<Long> deptIds) {
        return selectList(AdminUserDO::getDeptId, deptIds);
    }

}

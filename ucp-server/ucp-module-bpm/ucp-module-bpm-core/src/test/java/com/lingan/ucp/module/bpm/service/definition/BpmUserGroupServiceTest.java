package com.lingan.ucp.module.bpm.service.definition;

import static com.lingan.ucp.module.bpm.enums.ErrorCodeConstants.*;

import static org.assertj.core.api.Assertions.*;

import com.lingan.ucp.framework.common.enums.CommonStatusEnum;
import com.lingan.ucp.framework.common.exception.ServiceException;
import com.lingan.ucp.module.bpm.controller.admin.definition.vo.group.BpmUserGroupPageReqVO;
import com.lingan.ucp.module.bpm.controller.admin.definition.vo.group.BpmUserGroupSaveReqVO;
import com.lingan.ucp.module.bpm.dal.dataobject.definition.BpmUserGroupDO;
import com.lingan.ucp.module.bpm.dal.mysql.definition.BpmUserGroupMapper;
import com.lingan.ucp.module.bpm.support.BpmDatabaseTest;

import jakarta.annotation.Resource;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;

import java.time.LocalDateTime;
import java.util.Set;

/** 用户组配置、成员集合读写与禁用校验，夹具在当前开发库事务内回滚。 */
@Import(BpmUserGroupServiceImpl.class)
public class BpmUserGroupServiceTest extends BpmDatabaseTest {
    @Resource private BpmUserGroupServiceImpl userGroupService;
    @Resource private BpmUserGroupMapper userGroupMapper;

    @Test
    void testCreateUserGroup_success() {
        var request = request();
        var id = userGroupService.createUserGroup(request);
        assertThat(id).isNotNull();
        assertThat(userGroupMapper.selectById(id))
                .usingRecursiveComparison()
                .comparingOnlyFields("id", "name", "description", "status", "userIds")
                .isEqualTo(request);
    }

    @Test
    void testUpdateUserGroup_success() {
        var old = row("before");
        userGroupMapper.insert(old);
        var request = request().setId(old.getId()).setUserIds(Set.of(-903L));
        userGroupService.updateUserGroup(request);
        assertThat(userGroupMapper.selectById(old.getId()))
                .usingRecursiveComparison()
                .comparingOnlyFields("id", "name", "description", "status", "userIds")
                .isEqualTo(request);
    }

    @Test
    void testUpdateUserGroup_notExists() {
        assertThatThrownBy(() -> userGroupService.updateUserGroup(request()))
                .isInstanceOf(ServiceException.class)
                .extracting("code")
                .isEqualTo(USER_GROUP_NOT_EXISTS.getCode());
    }

    @Test
    void testDeleteUserGroup_success() {
        var old = row("delete");
        userGroupMapper.insert(old);
        userGroupService.deleteUserGroup(old.getId());
        assertThat(userGroupMapper.selectById(old.getId())).isNull();
    }

    @Test
    void testDeleteUserGroup_notExists() {
        assertThatThrownBy(() -> userGroupService.deleteUserGroup(nextId()))
                .isInstanceOf(ServiceException.class)
                .extracting("code")
                .isEqualTo(USER_GROUP_NOT_EXISTS.getCode());
    }

    @Test
    void testGetUserGroupPage() {
        var match = row("match");
        userGroupMapper.insert(match);
        userGroupMapper.insert(row("other"));
        userGroupMapper.insert(
                row("status")
                        .setName(match.getName())
                        .setStatus(CommonStatusEnum.DISABLE.getStatus()));
        var otherDate = row("date").setName(match.getName());
        otherDate.setCreateTime(LocalDateTime.of(2024, 2, 2, 0, 0));
        userGroupMapper.insert(otherDate);
        var query = new BpmUserGroupPageReqVO();
        query.setName(prefix + "_mat");
        query.setStatus(CommonStatusEnum.ENABLE.getStatus());
        query.setCreateTime(
                new LocalDateTime[] {
                    LocalDateTime.of(2023, 2, 1, 0, 0), LocalDateTime.of(2023, 2, 28, 0, 0)
                });
        var page = userGroupService.getUserGroupPage(query);
        assertThat(page.getTotal()).isEqualTo(1);
        assertThat(page.getList()).extracting(BpmUserGroupDO::getId).containsExactly(match.getId());
        assertThat(page.getList().getFirst().getUserIds()).isEqualTo(match.getUserIds());
    }

    @Test
    void testValidUserGroups_disabledAndMissing() {
        var enabled = row("enabled");
        var disabled = row("disabled").setStatus(CommonStatusEnum.DISABLE.getStatus());
        userGroupMapper.insert(enabled);
        userGroupMapper.insert(disabled);
        userGroupService.validUserGroups(Set.of(enabled.getId()));
        assertThatThrownBy(() -> userGroupService.validUserGroups(Set.of(disabled.getId())))
                .isInstanceOf(ServiceException.class)
                .extracting("code")
                .isEqualTo(USER_GROUP_IS_DISABLE.getCode());
        assertThatThrownBy(() -> userGroupService.validUserGroups(Set.of(nextId())))
                .isInstanceOf(ServiceException.class)
                .extracting("code")
                .isEqualTo(USER_GROUP_NOT_EXISTS.getCode());
    }

    private BpmUserGroupSaveReqVO request() {
        return new BpmUserGroupSaveReqVO()
                .setId(nextId())
                .setName(prefix + "_request")
                .setDescription("测试用户组")
                .setStatus(CommonStatusEnum.ENABLE.getStatus())
                .setUserIds(Set.of(-901L, -902L));
    }

    private BpmUserGroupDO row(String suffix) {
        var value =
                new BpmUserGroupDO()
                        .setId(nextId())
                        .setName(prefix + "_" + suffix)
                        .setDescription("测试用户组")
                        .setStatus(CommonStatusEnum.ENABLE.getStatus())
                        .setUserIds(Set.of(-901L, -902L));
        value.setCreateTime(LocalDateTime.of(2023, 2, 2, 0, 0));
        return value;
    }
}

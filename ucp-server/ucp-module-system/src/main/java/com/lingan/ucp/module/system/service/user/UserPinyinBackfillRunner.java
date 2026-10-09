package com.lingan.ucp.module.system.service.user;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lingan.ucp.module.system.dal.dataobject.user.AdminUserDO;
import com.lingan.ucp.module.system.dal.mysql.user.AdminUserMapper;
import com.lingan.ucp.module.system.util.UserPinyinConverter;
import com.lingan.ucp.framework.tenant.core.util.TenantUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 历史用户昵称拼音回填任务。
 *
 * <p>默认关闭，仅在数据库结构迁移完成后显式开启。任务只处理空字段，重复执行安全。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.user-pinyin", name = "backfill-enabled", havingValue = "true")
public class UserPinyinBackfillRunner implements ApplicationRunner {

    private static final int BATCH_SIZE = 500;

    private final AdminUserMapper userMapper;
    private final UserPinyinConverter userPinyinConverter;

    @Override
    public void run(ApplicationArguments args) {
        TenantUtils.executeIgnore(this::doBackfill);
    }

    private void doBackfill() {
        long updatedCount = 0;
        while (true) {
            Page<AdminUserDO> page = userMapper.selectPageMissingNicknamePinyin(
                    new Page<>(1, BATCH_SIZE, false));
            if (page.getRecords().isEmpty()) {
                break;
            }
            for (AdminUserDO user : page.getRecords()) {
                UserPinyinConverter.UserPinyin pinyin = userPinyinConverter.convert(user.getNickname());
                userMapper.updateById(new AdminUserDO().setId(user.getId())
                        .setNicknamePinyin(pinyin.fullPinyin())
                        .setNicknamePinyinInitial(pinyin.initials()));
                updatedCount++;
            }
        }
        log.info("用户昵称拼音历史回填完成，更新用户数：{}", updatedCount);
    }
}

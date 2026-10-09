package com.lingan.ucp.module.msg.web.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.lingan.ucp.module.msg.web.entity.SysMsg;
import com.lingan.ucp.module.msg.web.mapper.SysMsgMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class SysMsgService extends ServiceImpl<SysMsgMapper, SysMsg> {

}

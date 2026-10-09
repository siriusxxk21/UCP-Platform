package com.lingan.ucp.module.system.legacy.vo;

import lombok.Data;

import java.util.List;

@Data
public class UserPageVO {

    private Long total;

    private List<UserVO> list;
}

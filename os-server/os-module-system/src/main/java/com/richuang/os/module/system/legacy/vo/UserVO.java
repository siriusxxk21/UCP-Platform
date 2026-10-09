package com.richuang.os.module.system.legacy.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class UserVO {

    private String id;

    private String username;

    private String nickname;

    private String avatar;

    private String email;

    private String phone;

    private String orgId;

    private String orgName;

    private String deptId;

    private String deptName;

    private Integer userType;

    private Integer dataScope;

    private String post;

    private Integer status;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime createTime;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime updateTime;
}

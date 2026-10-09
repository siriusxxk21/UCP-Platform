package com.lingan.ucp.module.msg.web.vo;

import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;

@Data
@Accessors(chain = true)
public class SysMsgSubscribeAckInfo implements Serializable {
    private String type;

    private Object body;
}

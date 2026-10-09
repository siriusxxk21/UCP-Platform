package com.lingan.ucp.module.msg.core.channel;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

@Data
@AllArgsConstructor
public class NoticeChannel {
    private String code;
    private String name;
    private String description;
    private List<Metadata> metadataList;
}

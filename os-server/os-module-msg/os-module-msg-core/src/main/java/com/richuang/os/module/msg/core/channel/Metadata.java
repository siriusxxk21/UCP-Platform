package com.richuang.os.module.msg.core.channel;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class Metadata {
    private String code;
    private String name;
    private MetadataType type;
    private String description;
    private boolean required;
    public static Metadata of(String code, String name, MetadataType type, String description, Boolean required) {
        return new Metadata(code, name, type, description, required);
    }
}

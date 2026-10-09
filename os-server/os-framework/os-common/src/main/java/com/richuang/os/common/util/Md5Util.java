package com.richuang.os.common.util;

import org.springframework.util.DigestUtils;

import java.nio.charset.StandardCharsets;

public class Md5Util {
    public static void main(String[] args) {
        String encryptedPassword = DigestUtils.md5DigestAsHex(
                "123456".getBytes(StandardCharsets.UTF_8));

        System.out.println(encryptedPassword);
    }
}

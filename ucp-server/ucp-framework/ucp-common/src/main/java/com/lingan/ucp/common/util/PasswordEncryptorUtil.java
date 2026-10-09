package com.lingan.ucp.common.util;

import org.jasypt.encryption.StringEncryptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 密码加密工具类
 */
@Component
public class PasswordEncryptorUtil {

    @Autowired
    private StringEncryptor stringEncryptor;

    /**
     * 加密密码
     *
     * @param password 原始密码
     * @return 加密后的密码
     */
    public String encrypt(String password) {
        return stringEncryptor.encrypt(password);
    }

    /**
     * 解密密码
     *
     * @param encryptedPassword 加密的密码
     * @return 解密后的密码
     */
    public String decrypt(String encryptedPassword) {
        return stringEncryptor.decrypt(encryptedPassword);
    }

    /**
     * 测试密码是否匹配
     *
     * @param rawPassword    原始密码（前端传入的明文）
     * @param storedPassword 数据库存储的密码（必须是ENC加密格式）
     * @return 是否匹配
     */
    public boolean matches(String rawPassword, String storedPassword) {
        if (storedPassword == null || storedPassword.isEmpty()) {
            return rawPassword == null || rawPassword.isEmpty();
        }

        // 解密后比较
        String decrypted = decrypt(storedPassword);
        return rawPassword.equals(decrypted);
    }
}
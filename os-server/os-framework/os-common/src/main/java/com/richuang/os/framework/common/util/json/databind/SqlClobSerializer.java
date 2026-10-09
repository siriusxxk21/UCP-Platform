package com.richuang.os.framework.common.util.json.databind;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.annotation.JacksonStdImpl;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.sql.Clob;
import java.sql.SQLException;

/**
 * JDBC Clob 对象序列化器
 * <p>
 * 将达梦 (DM8)、Oracle 等数据库返回的 java.sql.Clob / DmdbClob 对象转换为普通的 String 字符串序列化，
 * 避免 Jackson 默认按照 JavaBean 反射 Clob.getConnection() 导致循环引用和嵌套深度超限 (depth > 1000)。
 * </p>
 */
@JacksonStdImpl
public class SqlClobSerializer extends JsonSerializer<Clob> {

    public static final SqlClobSerializer INSTANCE = new SqlClobSerializer();

    @Override
    public void serialize(Clob value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
        if (value == null) {
            gen.writeNull();
            return;
        }
        try {
            long length = value.length();
            if (length == 0) {
                gen.writeString("");
                return;
            }
            if (length <= Integer.MAX_VALUE) {
                gen.writeString(value.getSubString(1, (int) length));
                return;
            }
            try (Reader reader = value.getCharacterStream();
                 BufferedReader bufferedReader = new BufferedReader(reader)) {
                StringBuilder sb = new StringBuilder();
                char[] buffer = new char[4096];
                int charsRead;
                while ((charsRead = bufferedReader.read(buffer)) != -1) {
                    sb.append(buffer, 0, charsRead);
                }
                gen.writeString(sb.toString());
            }
        } catch (SQLException e) {
            gen.writeString(value.toString());
        }
    }
}

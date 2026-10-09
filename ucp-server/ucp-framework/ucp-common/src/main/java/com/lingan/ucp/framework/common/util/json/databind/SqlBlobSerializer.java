package com.lingan.ucp.framework.common.util.json.databind;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.annotation.JacksonStdImpl;

import java.io.IOException;
import java.sql.Blob;
import java.sql.SQLException;

/**
 * JDBC Blob 对象序列化器
 * <p>
 * 将数据库返回的 java.sql.Blob / DmdbBlob 对象转换为字节数组 / Base64 输出，
 * 避免 Jackson 反射 Blob 对象内部的 Connection 导致嵌套死循环。
 * </p>
 */
@JacksonStdImpl
public class SqlBlobSerializer extends JsonSerializer<Blob> {

    public static final SqlBlobSerializer INSTANCE = new SqlBlobSerializer();

    @Override
    public void serialize(Blob value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
        if (value == null) {
            gen.writeNull();
            return;
        }
        try {
            long length = value.length();
            if (length == 0) {
                gen.writeBinary(new byte[0]);
                return;
            }
            byte[] bytes = value.getBytes(1, (int) Math.min(length, Integer.MAX_VALUE));
            gen.writeBinary(bytes);
        } catch (SQLException e) {
            gen.writeNull();
        }
    }
}

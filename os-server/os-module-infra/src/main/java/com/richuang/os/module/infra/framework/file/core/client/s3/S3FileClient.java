package com.richuang.os.module.infra.framework.file.core.client.s3;

import cn.hutool.core.io.IoUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpUtil;
import com.richuang.os.framework.common.util.http.HttpUtils;
import com.richuang.os.module.infra.framework.file.core.client.AbstractFileClient;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.retry.RetryMode;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.ListPartsRequest;
import software.amazon.awssdk.services.s3.model.ListPartsResponse;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.UploadPartPresignRequest;

import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;

/**
 * 基于 S3 协议的文件客户端，实现 MinIO、阿里云、腾讯云、七牛云、华为云等云服务
 *
 * @author os
 */
@Slf4j
public class S3FileClient extends AbstractFileClient<S3FileClientConfig> {

    private static final Duration EXPIRATION_DEFAULT = Duration.ofHours(24);

    private S3Client client;
    private S3Presigner presigner;

    public S3FileClient(Long id, S3FileClientConfig config) {
        super(id, config);
    }

    @Override
    protected void doInit() {
        // 补全 domain
        if (StrUtil.isEmpty(config.getDomain())) {
            config.setDomain(buildDomain());
        }
        // 初始化 S3 客户端
        // 优先级：配置的 region > 从 endpoint 解析的 region > 默认值 us-east-1
        String regionStr = resolveRegion();
        Region region = Region.of(regionStr);
        AwsCredentialsProvider credentialsProvider = StaticCredentialsProvider.create(
                AwsBasicCredentials.create(config.getAccessKey(), config.getAccessSecret()));
        URI endpoint = URI.create(buildEndpoint());
        URI presignerEndpoint = URI.create(buildPresignerEndpoint());
        S3Configuration serviceConfiguration = S3Configuration.builder() // Path-style 访问
                .pathStyleAccessEnabled(Boolean.TRUE.equals(config.getEnablePathStyleAccess()))
                .chunkedEncodingEnabled(false) // 禁用分块编码，参见 https://t.zsxq.com/kBy57
                .build();
        client = S3Client.builder()
                .credentialsProvider(credentialsProvider)
                .region(region)
                .endpointOverride(endpoint)
                .serviceConfiguration(serviceConfiguration)
                .overrideConfiguration(o -> o
                        .retryPolicy(RetryMode.STANDARD)
                        .apiCallTimeout(Duration.ofMinutes(5))
                        .apiCallAttemptTimeout(Duration.ofSeconds(30)))
                .build();
        presigner = S3Presigner.builder()
                .credentialsProvider(credentialsProvider)
                .region(region)
                .endpointOverride(presignerEndpoint)
                .serviceConfiguration(serviceConfiguration)
                .build();

        // 确保 Bucket 存在，避免后续上传/下载操作因 Bucket 不存在而失败
        ensureBucketExists();
    }

    /**
     * 检查并确保 Bucket 存在，若不存在则自动创建。
     * <p>
     * 通过 headBucket 探测目标 Bucket，收到 {@link NoSuchBucketException} 时
     * 调用 createBucket 自动创建。适用于 MinIO 等自建 S3 兼容存储首次部署、
     * 或 Flyway 脚本仅注册配置但未创建物理 Bucket 的场景。
     */
    private void ensureBucketExists() {
        String bucket = config.getBucket();
        try {
            client.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
            log.info("[ensureBucketExists][bucket({}) 已存在]", bucket);
        } catch (NoSuchBucketException e) {
            log.warn("[ensureBucketExists][bucket({}) 不存在，自动创建]", bucket);
            client.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
            log.info("[ensureBucketExists][bucket({}) 创建成功]", bucket);
        }
    }

    @Override
    public String upload(byte[] content, String path, String type) {
        // 构造 PutObjectRequest
        PutObjectRequest putRequest = PutObjectRequest.builder()
                .bucket(config.getBucket())
                .key(path)
                .contentType(type)
                .contentLength((long) content.length)
                .build();
        // 上传文件
        client.putObject(putRequest, RequestBody.fromBytes(content));
        // 拼接返回路径
        return presignGetUrl(path, null);
    }

    @Override
    public String upload(InputStream content, long contentLength, String path, String type) {
        // 流式上传必须显式给出长度，否则 SDK 会把内容缓冲到内存后再发送
        PutObjectRequest putRequest = PutObjectRequest.builder()
                .bucket(config.getBucket())
                .key(path)
                .contentType(type)
                .contentLength(contentLength)
                .build();
        client.putObject(putRequest, RequestBody.fromInputStream(content, contentLength));
        return presignGetUrl(path, null);
    }

    @Override
    public void delete(String path) {
        DeleteObjectRequest deleteRequest = DeleteObjectRequest.builder()
                .bucket(config.getBucket())
                .key(path)
                .build();
        client.deleteObject(deleteRequest);
    }

    @Override
    public byte[] getContent(String path) {
        GetObjectRequest getRequest = GetObjectRequest.builder()
                .bucket(config.getBucket())
                .key(path)
                .build();
        return IoUtil.readBytes(client.getObject(getRequest));
    }

    @Override
    public Long getContentLength(String path) {
        try {
            return client.headObject(HeadObjectRequest.builder()
                    .bucket(config.getBucket()).key(path).build()).contentLength();
        } catch (NoSuchKeyException e) {
            return null;
        }
    }

    @Override
    public InputStream getContentStream(String path, long offset) {
        GetObjectRequest.Builder builder = GetObjectRequest.builder()
                .bucket(config.getBucket())
                .key(path);
        if (offset > 0) {
            // 交给对象存储裁剪区间，平台侧不下载前缀内容
            builder.range(StrUtil.format("bytes={}-", offset));
        }
        try {
            return client.getObject(builder.build());
        } catch (NoSuchKeyException e) {
            return null;
        }
    }

    @Override
    public String presignPutUrl(String path) {
        return presigner.presignPutObject(PutObjectPresignRequest.builder()
                .signatureDuration(EXPIRATION_DEFAULT)
                .putObjectRequest(b -> b.bucket(config.getBucket()).key(path)).build())
                .url().toString();
    }

    @Override
    public String presignGetUrl(String url, Integer expirationSeconds) {
        // 1. 将 url 转换为 path
        String path = StrUtil.removePrefix(url, config.getDomain() + "/");
        path = HttpUtils.decodeUrlPath(HttpUtils.removeUrlQuery(path));

        // 2.1 情况一：公开访问：无需签名
        // 考虑到老版本的兼容，所以必须是 config.getEnablePublicAccess() 为 false 时，才进行签名
        if (!BooleanUtil.isFalse(config.getEnablePublicAccess())) {
            return config.getDomain() + "/" + path;
        }

        // 2.2 情况二：私有访问：生成 GET 预签名 URL
        String finalPath = path;
        Duration expiration = expirationSeconds != null ? Duration.ofSeconds(expirationSeconds) : EXPIRATION_DEFAULT;
        URL signedUrl = presigner.presignGetObject(GetObjectPresignRequest.builder()
                .signatureDuration(expiration)
                .getObjectRequest(b -> b.bucket(config.getBucket()).key(finalPath)).build())
                .url();
        return signedUrl.toString();
    }

    /**
     * 基于 bucket + endpoint 构建访问的 Domain 地址
     *
     * @return Domain 地址
     */
    private String buildDomain() {
        // 如果已经是 http 或者 https，则不进行拼接.主要适配 MinIO
        if (HttpUtil.isHttp(config.getEndpoint()) || HttpUtil.isHttps(config.getEndpoint())) {
            return StrUtil.format("{}/{}", config.getEndpoint(), config.getBucket());
        }
        // 阿里云、腾讯云、华为云都适合。七牛云比较特殊，必须有自定义域名
        return StrUtil.format("https://{}.{}", config.getBucket(), config.getEndpoint());
    }

    /**
     * 节点地址补全协议头
     *
     * @return 节点地址
     */
    private String buildEndpoint() {
        // 如果已经是 http 或者 https，则不进行拼接
        if (HttpUtil.isHttp(config.getEndpoint()) || HttpUtil.isHttps(config.getEndpoint())) {
            return config.getEndpoint();
        }
        return StrUtil.format("https://{}", config.getEndpoint());
    }

    /**
     * presigner 节点地址
     *
     * @return 节点地址
     */
    private String buildPresignerEndpoint() {
        // 补全 domain
        if (StrUtil.isEmpty(config.getDomain())) {
            config.setDomain(buildDomain());
        }

        if (Boolean.TRUE.equals(config.getEnablePathStyleAccess())) {
            return StrUtil.removeSuffix(config.getDomain(), StrUtil.format("/{}", config.getBucket()));
        }
        return StrUtil.replace(config.getDomain(), StrUtil.format("://{}.", config.getBucket()), "://");
    }

    /**
     * 解析 AWS 区域
     * 优先级：配置的 region > 从 endpoint 解析的 region > 默认值 us-east-1
     *
     * @return 区域字符串
     */
    private String resolveRegion() {
        // 1. 如果配置了 region，直接使用
        if (StrUtil.isNotEmpty(config.getRegion())) {
            return config.getRegion();
        }

        // 2.1 尝试从 endpoint 中解析 region
        String endpoint = config.getEndpoint();
        if (StrUtil.isEmpty(endpoint)) {
            return "us-east-1";
        }

        // 2.2 移除协议头（http:// 或 https://）
        String host = endpoint;
        if (HttpUtil.isHttp(endpoint) || HttpUtil.isHttps(endpoint)) {
            try {
                host = URI.create(endpoint).getHost();
            } catch (Exception e) {
                // 解析失败，使用默认值
                return "us-east-1";
            }
        }
        if (StrUtil.isEmpty(host)) {
            return "us-east-1";
        }

        // 3.1 AWS S3 格式：s3.us-west-2.amazonaws.com 或 s3.amazonaws.com
        if (host.contains("amazonaws.com")) {
            // 匹配 s3.{region}.amazonaws.com 格式
            if (host.startsWith("s3.") && host.contains(".amazonaws.com")) {
                String regionPart = host.substring(3, host.indexOf(".amazonaws.com"));
                if (StrUtil.isNotEmpty(regionPart) && !regionPart.equals("accelerate")) {
                    return regionPart;
                }
            }
            // s3.amazonaws.com 或 s3-accelerate.amazonaws.com 使用默认值
            return "us-east-1";
        }
        // 3.2 阿里云 OSS 格式：oss-cn-beijing.aliyuncs.com
        if (host.contains(S3FileClientConfig.ENDPOINT_ALIYUN)) {
            // 匹配 oss-{region}.aliyuncs.com 格式
            if (host.startsWith("oss-") && host.contains("." + S3FileClientConfig.ENDPOINT_ALIYUN)) {
                String regionPart = host.substring(4, host.indexOf("." + S3FileClientConfig.ENDPOINT_ALIYUN));
                if (StrUtil.isNotEmpty(regionPart)) {
                    return regionPart;
                }
            }
        }
        // 3.3 腾讯云 COS 格式：cos.ap-shanghai.myqcloud.com
        if (host.contains(S3FileClientConfig.ENDPOINT_TENCENT)) {
            // 匹配 cos.{region}.myqcloud.com 格式
            if (host.startsWith("cos.") && host.contains("." + S3FileClientConfig.ENDPOINT_TENCENT)) {
                String regionPart = host.substring(4, host.indexOf("." + S3FileClientConfig.ENDPOINT_TENCENT));
                if (StrUtil.isNotEmpty(regionPart)) {
                    return regionPart;
                }
            }
        }

        // 3.4 其他情况（MinIO、七牛云等）使用默认值
        return "us-east-1";
    }

    // ========== Multipart Upload ==========

    /**
     * 初始化分片上传，返回 uploadId。
     *
     * @param path        对象存储路径
     * @param contentType 文件 Content-Type
     * @return uploadId
     */
    public String createMultipartUpload(String path, String contentType) {
        CreateMultipartUploadRequest request = CreateMultipartUploadRequest.builder()
                .bucket(config.getBucket())
                .key(path)
                .contentType(contentType)
                .build();
        return client.createMultipartUpload(request).uploadId();
    }

    /**
     * 为指定分片生成预签名 PUT URL，前端可直传该 part。
     *
     * @param path       对象存储路径
     * @param uploadId   分片上传 ID
     * @param partNumber 分片编号（从 1 开始）
     * @return 预签名 PUT URL
     */
    public String presignMultipartPutUrl(String path, String uploadId, int partNumber) {
        Duration expiration = Duration.ofMinutes(
                config.getMultipartExpirationMinutes() != null ? config.getMultipartExpirationMinutes() : 1440);
        return presigner.presignUploadPart(UploadPartPresignRequest.builder()
                .signatureDuration(expiration)
                .uploadPartRequest(b -> b
                        .bucket(config.getBucket())
                        .key(path)
                        .uploadId(uploadId)
                        .partNumber(partNumber))
                .build()).url().toString();
    }

    /**
     * 完成分片上传，合并所有 part。
     * <p>忽略前端传入的 ETag（浏览器 CORS 可能无法读取 ETag 响应头），
     * 改为通过 {@code listParts} 从 S3 服务端获取真实的 part 列表后再合并。
     *
     * @param path     对象存储路径
     * @param uploadId 分片上传 ID
     * @param parts    前端上报的分片列表（仅用于校验数量，ETag 不被信任）
     * @return 合并后的文件访问路径
     */
    public String completeMultipartUpload(String path, String uploadId, List<CompletedPart> parts) {
        // 从 S3 服务端获取真实的 part 列表（含正确的 ETag）
        List<CompletedPart> realParts = listParts(path, uploadId);
        if (realParts.isEmpty()) {
            throw new RuntimeException("分片上传完成失败：S3 上未找到任何已上传的分片，uploadId=" + uploadId);
        }

        CompleteMultipartUploadRequest request = CompleteMultipartUploadRequest.builder()
                .bucket(config.getBucket())
                .key(path)
                .uploadId(uploadId)
                .multipartUpload(b -> b.parts(realParts))
                .build();
        client.completeMultipartUpload(request);
        return path;
    }

    /**
     * 从 S3 服务端列出已上传的分片及其 ETag。
     * <p>用于替代前端上报的 ETag（浏览器 CORS 可能无法读取 ETag 响应头）。
     *
     * @param path     对象存储路径
     * @param uploadId 分片上传 ID
     * @return 按 partNumber 升序排列的已完成分片列表
     */
    private List<CompletedPart> listParts(String path, String uploadId) {
        ListPartsRequest request = ListPartsRequest.builder()
                .bucket(config.getBucket())
                .key(path)
                .uploadId(uploadId)
                .build();
        ListPartsResponse response = client.listParts(request);
        return response.parts().stream()
                .map(p -> CompletedPart.builder()
                        .partNumber(p.partNumber())
                        .eTag(p.eTag())
                        .build())
                .sorted(Comparator.comparingInt(CompletedPart::partNumber))
                .collect(java.util.stream.Collectors.toList());
    }

    /**
     * 取消分片上传，清理已上传的 part。
     *
     * @param path     对象存储路径
     * @param uploadId 分片上传 ID
     */
    public void abortMultipartUpload(String path, String uploadId) {
        AbortMultipartUploadRequest request = AbortMultipartUploadRequest.builder()
                .bucket(config.getBucket())
                .key(path)
                .uploadId(uploadId)
                .build();
        client.abortMultipartUpload(request);
    }

    /**
     * 获取分片大小（单位：字节）。
     *
     * @return 分片大小（字节）
     */
    public long getMultipartPartSize() {
        int mb = config.getMultipartPartSize() != null ? config.getMultipartPartSize() : 8;
        return (long) mb * 1024 * 1024;
    }

}

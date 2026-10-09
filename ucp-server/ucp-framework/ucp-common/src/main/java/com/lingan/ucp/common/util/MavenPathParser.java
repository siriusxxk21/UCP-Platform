package com.lingan.ucp.common.util;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.util.regex.Pattern;

/**
 * Maven 仓库路径解析工具
 * 从文件在 Maven 仓库中的路径解析 GAV 坐标
 * <p>
 * 标准 Maven 仓库路径格式:
 * groupId/artifactId/version/artifactId-version[-classifier].extension
 * 例如: com/google/guava/guava/31.1-jre/guava-31.1-jre.jar
 */
@Slf4j
public class MavenPathParser {

    // 版本号正则：以数字开头，可包含数字、点、横线、下划线等
    private static final Pattern VERSION_PATTERN = Pattern.compile("^[0-9]");

    /**
     * 从 Maven 仓库路径解析 GAV 坐标
     *
     * @param file     文件对象
     * @param basePath 解压根目录的绝对路径（用于计算相对路径）
     * @return Maven坐标信息
     */
    public static MavenCoordinate parse(File file, String basePath) {
        if (file == null || !file.exists()) {
            return null;
        }

        try {
            // 获取相对于根目录的路径
            String relativePath = getRelativePath(file, basePath);
            return parseFromPath(relativePath, file.getName());
        } catch (Exception e) {
            log.warn("解析 Maven 路径失败: {}", file.getAbsolutePath(), e);
            return null;
        }
    }

    /**
     * 从相对路径解析 GAV 坐标
     *
     * @param relativePath 相对于仓库根目录的路径，如: com/google/guava/guava/31.1-jre/guava-31.1-jre.jar
     * @param fileName     文件名
     * @return Maven坐标信息
     */
    public static MavenCoordinate parseFromPath(String relativePath, String fileName) {
        if (relativePath == null || relativePath.isEmpty()) {
            return null;
        }

        // 统一使用正斜杠
        relativePath = relativePath.replace("\\", "/");

        // 分割路径
        String[] parts = relativePath.split("/");
        if (parts.length < 3) {
            log.warn("路径层级不足，无法解析GAV: {}", relativePath);
            return null;
        }

        MavenCoordinate coordinate = new MavenCoordinate();

        // 最后一部分是文件名
        String lastPart = parts[parts.length - 1];

        // 倒数第二部分应该是版本号目录
        String versionDir = parts[parts.length - 2];

        // 倒数第三部分是 artifactId
        String artifactId = parts[parts.length - 3];

        // 前面所有部分构成 groupId
        StringBuilder groupIdBuilder = new StringBuilder();
        for (int i = 0; i < parts.length - 3; i++) {
            if (i > 0) {
                groupIdBuilder.append(".");
            }
            groupIdBuilder.append(parts[i]);
        }

        coordinate.setGroupId(groupIdBuilder.toString());
        coordinate.setArtifactId(artifactId);
        coordinate.setVersion(versionDir);
        coordinate.setFileName(fileName);

        // 验证解析结果
        if (!coordinate.isValid()) {
            log.warn("解析出的坐标不完整: groupId={}, artifactId={}, version={}",
                    coordinate.getGroupId(), coordinate.getArtifactId(), coordinate.getVersion());
            return null;
        }

        log.debug("解析 Maven 坐标成功: {} -> {}", relativePath, coordinate.getGav());
        return coordinate;
    }

    /**
     * 从文件名解析版本号和分类器
     * 文件名格式: artifactId-version[-classifier].extension
     */
    private static void parseFileName(String fileName, MavenCoordinate coordinate) {
        // 移除扩展名
        String nameWithoutExt = fileName;
        int lastDot = nameWithoutExt.lastIndexOf('.');
        if (lastDot > 0) {
            nameWithoutExt = nameWithoutExt.substring(0, lastDot);
        }

        String artifactId = coordinate.getArtifactId();

        // 文件名应该以 artifactId- 开头
        if (!nameWithoutExt.startsWith(artifactId + "-")) {
            log.warn("文件名格式不符合预期: {}，期望以 {}- 开头", fileName, artifactId);
            return;
        }

        // 提取 version 和 classifier
        String afterArtifactId = nameWithoutExt.substring(artifactId.length() + 1);

        // 尝试识别版本号（以数字开头）
        // 格式可能是: 1.0.0 或 1.0.0-SNAPSHOT 或 1.0.0-sources 等
        String[] versionParts = afterArtifactId.split("-", 2);
        if (versionParts.length > 0 && VERSION_PATTERN.matcher(versionParts[0]).matches()) {
            // 版本号可能包含 -SNAPSHOT 或 -sources 等后缀
            coordinate.setVersion(versionParts[0]);
            if (versionParts.length > 1) {
                coordinate.setClassifier(versionParts[1]);
            }
        }
    }

    /**
     * 获取相对于根目录的路径
     */
    private static String getRelativePath(File file, String basePath) {
        String filePath = file.getAbsolutePath();
        String base = new File(basePath).getAbsolutePath();

        if (filePath.startsWith(base)) {
            String relative = filePath.substring(base.length());
            if (relative.startsWith(File.separator)) {
                relative = relative.substring(1);
            }
            return relative;
        }

        // 如果无法获取相对路径，返回文件名
        return file.getName();
    }

    /**
     * 构建 Nexus 上传路径
     * 格式: groupId/artifactId/version/filename
     * 其中 groupId 的点号替换为斜杠
     */
    public static String buildNexusPath(String groupId, String artifactId, String version, String fileName) {
        String groupPath = groupId.replace(".", "/");
        return String.format("%s/%s/%s/%s", groupPath, artifactId, version, fileName);
    }

    /**
     * 判断文件是否为有效的 Maven 包文件
     */
    public static boolean isMavenPackageFile(String fileName) {
        if (fileName == null || fileName.isEmpty()) {
            return false;
        }
        String lower = fileName.toLowerCase();
        return lower.endsWith(".jar") ||
                lower.endsWith(".war") ||
                lower.endsWith(".ear") ||
                lower.endsWith(".pom") ||
                lower.endsWith(".zip") ||
                lower.endsWith(".tar.gz") ||
                lower.endsWith(".tgz") ||
                lower.endsWith(".sha1") ||
                lower.endsWith(".sha256") ||
                lower.endsWith(".sha512") ||
                lower.endsWith(".md5");
    }

    /**
     * 判断文件是否为校验和文件
     */
    public static boolean isChecksumFile(String fileName) {
        if (fileName == null) return false;
        String lower = fileName.toLowerCase();
        return lower.endsWith(".sha1") ||
                lower.endsWith(".sha256") ||
                lower.endsWith(".sha512") ||
                lower.endsWith(".md5");
    }

    /**
     * Maven 坐标信息
     */
    @Data
    public static class MavenCoordinate {
        private String groupId;
        private String artifactId;
        private String version;
        private String classifier;
        private String fileName;
        private String packaging;

        /**
         * 获取 GAV 字符串
         */
        public String getGav() {
            return String.format("%s:%s:%s", groupId, artifactId, version);
        }

        /**
         * 检查坐标是否有效
         */
        public boolean isValid() {
            return groupId != null && !groupId.isEmpty() &&
                    artifactId != null && !artifactId.isEmpty() &&
                    version != null && !version.isEmpty();
        }

        /**
         * 获取文件扩展名对应的 packaging 类型
         */
        public String getPackaging() {
            if (fileName == null) return "jar";
            if (fileName.endsWith(".pom")) return "pom";
            if (fileName.endsWith(".war")) return "war";
            if (fileName.endsWith(".ear")) return "ear";
            return "jar";
        }
    }
}

package com.richuang.os.common.util;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Maven 包元数据解析工具
 * 从 jar/pom 文件中解析 groupId, artifactId, version
 */
@Slf4j
public class MavenMetadataParser {

    /**
     * 从文件解析 Maven 元数据
     * 支持 jar, war, pom 文件
     */
    public static MavenMetadata parse(File file) {
        String fileName = file.getName().toLowerCase();

        try {
            if (fileName.endsWith(".pom")) {
                return parsePomFile(file);
            } else if (fileName.endsWith(".jar") || fileName.endsWith(".war")) {
                return parseJarFile(file);
            } else if (fileName.endsWith(".zip")) {
                return parseZipFile(file);
            }
        } catch (Exception e) {
            log.warn("解析 Maven 元数据失败: {}", file.getName(), e);
        }

        // 如果解析失败，尝试从文件名解析
        return parseFromFileName(file.getName());
    }

    /**
     * 解析 pom.xml 文件
     */
    private static MavenMetadata parsePomFile(File pomFile) {
        try (InputStream is = new FileInputStream(pomFile)) {
            return parsePomXml(is);
        } catch (Exception e) {
            log.warn("解析 POM 文件失败: {}", pomFile.getName(), e);
        }
        return null;
    }

    /**
     * 从 jar/war 文件中解析
     * 优先读取 pom.xml，如果不存在则从 META-INF/MANIFEST.MF 或文件名解析
     */
    private static MavenMetadata parseJarFile(File jarFile) {
        try (JarFile jar = new JarFile(jarFile)) {
            // 1. 尝试从 jar 内的 pom.xml 解析
            JarEntry pomEntry = findPomEntry(jar);
            if (pomEntry != null) {
                try (InputStream is = jar.getInputStream(pomEntry)) {
                    MavenMetadata metadata = parsePomXml(is);
                    if (metadata != null && metadata.isValid()) {
                        return metadata;
                    }
                }
            }

            // 2. 尝试从 MANIFEST.MF 解析
            JarEntry manifestEntry = jar.getJarEntry("META-INF/MANIFEST.MF");
            if (manifestEntry != null) {
                try (InputStream is = jar.getInputStream(manifestEntry)) {
                    MavenMetadata metadata = parseManifest(is);
                    if (metadata != null && metadata.isValid()) {
                        return metadata;
                    }
                }
            }

        } catch (Exception e) {
            log.warn("解析 JAR 文件失败: {}", jarFile.getName(), e);
        }

        // 3. 从文件名解析
        return parseFromFileName(jarFile.getName());
    }

    /**
     * 从 zip 文件中解析（用于某些特殊情况）
     */
    private static MavenMetadata parseZipFile(File zipFile) {
        try (ZipFile zip = new ZipFile(zipFile)) {
            // 查找 pom.xml
            java.util.Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.getName().endsWith("pom.xml") || entry.getName().endsWith(".pom")) {
                    try (InputStream is = zip.getInputStream(entry)) {
                        MavenMetadata metadata = parsePomXml(is);
                        if (metadata != null && metadata.isValid()) {
                            return metadata;
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("解析 ZIP 文件失败: {}", zipFile.getName(), e);
        }
        return null;
    }

    /**
     * 在 jar 中查找 pom.xml
     */
    private static JarEntry findPomEntry(JarFile jar) {
        // 优先查找 META-INF/maven/*/pom.xml
        java.util.Enumeration<JarEntry> entries = jar.entries();
        JarEntry fallbackEntry = null;

        while (entries.hasMoreElements()) {
            JarEntry entry = entries.nextElement();
            String name = entry.getName();

            // 优先匹配 META-INF/maven/ 下的 pom.xml
            if (name.startsWith("META-INF/maven/") && name.endsWith("/pom.xml")) {
                return entry;
            }

            // 记录其他 pom.xml 作为备选
            if (name.endsWith("pom.xml") || name.endsWith(".pom")) {
                fallbackEntry = entry;
            }
        }

        return fallbackEntry;
    }

    /**
     * 解析 pom.xml 输入流
     */
    private static MavenMetadata parsePomXml(InputStream is) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            // 禁用 DTD 验证，防止外部实体攻击
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setNamespaceAware(false);

            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(is);

            Element root = doc.getDocumentElement();

            MavenMetadata metadata = new MavenMetadata();
            metadata.setGroupId(getElementText(root, "groupId"));
            metadata.setArtifactId(getElementText(root, "artifactId"));
            metadata.setVersion(getElementText(root, "version"));

            // 如果 groupId 为空，尝试从 parent 获取
            if (metadata.getGroupId() == null) {
                NodeList parents = root.getElementsByTagName("parent");
                if (parents.getLength() > 0) {
                    Element parent = (Element) parents.item(0);
                    metadata.setGroupId(getElementText(parent, "groupId"));
                    if (metadata.getVersion() == null) {
                        metadata.setVersion(getElementText(parent, "version"));
                    }
                }
            }

            return metadata;
        } catch (Exception e) {
            log.warn("解析 POM XML 失败", e);
        }
        return null;
    }

    /**
     * 获取 XML 元素文本
     */
    private static String getElementText(Element parent, String tagName) {
        NodeList nodes = parent.getElementsByTagName(tagName);
        if (nodes.getLength() > 0) {
            Node node = nodes.item(0);
            if (node != null && node.getTextContent() != null) {
                String text = node.getTextContent().trim();
                // 处理 ${xxx} 变量（简单处理，使用默认值）
                if (text.startsWith("${") && text.endsWith("}")) {
                    return null; // 变量无法解析，返回 null
                }
                return text;
            }
        }
        return null;
    }

    /**
     * 解析 MANIFEST.MF
     */
    private static MavenMetadata parseManifest(InputStream is) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            MavenMetadata metadata = new MavenMetadata();
            String line;

            while ((line = reader.readLine()) != null) {
                if (line.startsWith("Implementation-Title:")) {
                    metadata.setArtifactId(line.substring(21).trim());
                } else if (line.startsWith("Implementation-Version:")) {
                    metadata.setVersion(line.substring(23).trim());
                } else if (line.startsWith("Implementation-Vendor:")) {
                    metadata.setGroupId(line.substring(22).trim());
                }
            }

            return metadata.isValid() ? metadata : null;
        } catch (Exception e) {
            log.warn("解析 MANIFEST.MF 失败", e);
        }
        return null;
    }

    /**
     * 从文件名解析（最后的备选方案）
     * Maven 标准格式: artifactId-version[-classifier].extension
     * 或: groupId-artifactId-version.extension（某些情况）
     */
    private static MavenMetadata parseFromFileName(String fileName) {
        // 移除扩展名
        String name = fileName;
        int lastDot = name.lastIndexOf('.');
        if (lastDot > 0) {
            name = name.substring(0, lastDot);
        }

        MavenMetadata metadata = new MavenMetadata();

        // 尝试匹配标准 Maven 格式
        // 格式: artifactId-version[-classifier]
        // version 通常包含数字和点，如 1.0.0, 2.3.1-SNAPSHOT
        Pattern pattern = Pattern.compile("^(.+)-([0-9]+\\.[0-9].*)$");
        Matcher matcher = pattern.matcher(name);

        if (matcher.matches()) {
            metadata.setArtifactId(matcher.group(1));
            metadata.setVersion(matcher.group(2));
            // groupId 无法从文件名确定，设为 null
        } else {
            // 无法解析，使用文件名作为 artifactId
            metadata.setArtifactId(name);
        }

        return metadata;
    }

    /**
     * 判断是否为有效的 Maven 包文件
     */
    public static boolean isMavenPackage(File file) {
        String name = file.getName().toLowerCase();
        return name.endsWith(".jar") || name.endsWith(".war") ||
                name.endsWith(".pom") || name.endsWith(".zip");
    }

    /**
     * Maven 元数据
     */
    @Data
    public static class MavenMetadata {
        private String groupId;
        private String artifactId;
        private String version;

        /**
         * 检查元数据是否有效（至少包含 artifactId 和 version）
         */
        public boolean isValid() {
            return artifactId != null && !artifactId.isEmpty() &&
                    version != null && !version.isEmpty();
        }

        /**
         * 获取完整的包名
         */
        public String getFullName() {
            if (groupId != null && !groupId.isEmpty()) {
                return groupId + ":" + artifactId + ":" + version;
            }
            return artifactId + ":" + version;
        }
    }
}

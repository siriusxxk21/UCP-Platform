package com.richuang.os.common.util;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.compress.archivers.ArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 压缩包解压工具类
 * 支持 zip, tar, tar.gz 格式
 */
@Slf4j
public class ArchiveUtil {

    /**
     * 支持的压缩包扩展名
     */
    public static final String[] SUPPORTED_EXTENSIONS = {".zip", ".tar", ".tar.gz", ".tgz"};

    /**
     * 检查文件是否为支持的压缩包格式
     */
    public static boolean isArchiveFile(String filename) {
        if (filename == null) return false;
        String lowerName = filename.toLowerCase();
        for (String ext : SUPPORTED_EXTENSIONS) {
            if (lowerName.endsWith(ext)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 获取压缩包类型
     */
    public static ArchiveType getArchiveType(String filename) {
        if (filename == null) return null;
        String lowerName = filename.toLowerCase();
        if (lowerName.endsWith(".zip")) {
            return ArchiveType.ZIP;
        } else if (lowerName.endsWith(".tar.gz") || lowerName.endsWith(".tgz")) {
            return ArchiveType.TAR_GZ;
        } else if (lowerName.endsWith(".tar")) {
            return ArchiveType.TAR;
        }
        return null;
    }

    /**
     * 解压压缩包到指定目录
     *
     * @param archiveFile 压缩包文件
     * @param destDir     目标目录
     * @return 解压后的文件列表
     */
    public static List<File> extract(File archiveFile, File destDir) throws IOException {
        ArchiveType type = getArchiveType(archiveFile.getName());
        if (type == null) {
            throw new IllegalArgumentException("不支持的压缩包格式: " + archiveFile.getName());
        }

        // 确保目标目录存在
        if (!destDir.exists()) {
            destDir.mkdirs();
        }

        return switch (type) {
            case ZIP -> extractZip(archiveFile, destDir);
            case TAR -> extractTar(archiveFile, destDir);
            case TAR_GZ -> extractTarGz(archiveFile, destDir);
        };
    }

    /**
     * 解压 ZIP 文件
     */
    private static List<File> extractZip(File zipFile, File destDir) throws IOException {
        List<File> extractedFiles = new ArrayList<>();

        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(zipFile))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }

                File outputFile = new File(destDir, entry.getName());
                // 安全检查：防止路径遍历攻击
                if (!outputFile.getCanonicalPath().startsWith(destDir.getCanonicalPath())) {
                    log.warn("检测到可疑路径，跳过: {}", entry.getName());
                    continue;
                }

                // 创建父目录
                outputFile.getParentFile().mkdirs();

                // 写入文件
                try (FileOutputStream fos = new FileOutputStream(outputFile)) {
                    byte[] buffer = new byte[8192];
                    int len;
                    while ((len = zis.read(buffer)) > 0) {
                        fos.write(buffer, 0, len);
                    }
                }

                extractedFiles.add(outputFile);
                log.debug("解压文件: {}", outputFile.getAbsolutePath());
            }
        }

        return extractedFiles;
    }

    /**
     * 解压 TAR 文件
     */
    private static List<File> extractTar(File tarFile, File destDir) throws IOException {
        List<File> extractedFiles = new ArrayList<>();

        try (TarArchiveInputStream tis = new TarArchiveInputStream(new FileInputStream(tarFile))) {
            ArchiveEntry entry;
            while ((entry = tis.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }

                File outputFile = new File(destDir, entry.getName());
                // 安全检查
                if (!outputFile.getCanonicalPath().startsWith(destDir.getCanonicalPath())) {
                    log.warn("检测到可疑路径，跳过: {}", entry.getName());
                    continue;
                }

                outputFile.getParentFile().mkdirs();

                try (FileOutputStream fos = new FileOutputStream(outputFile)) {
                    byte[] buffer = new byte[8192];
                    int len;
                    while ((len = tis.read(buffer)) > 0) {
                        fos.write(buffer, 0, len);
                    }
                }

                extractedFiles.add(outputFile);
                log.debug("解压文件: {}", outputFile.getAbsolutePath());
            }
        }

        return extractedFiles;
    }

    /**
     * 解压 TAR.GZ 文件
     */
    private static List<File> extractTarGz(File tarGzFile, File destDir) throws IOException {
        List<File> extractedFiles = new ArrayList<>();

        try (GzipCompressorInputStream gzis = new GzipCompressorInputStream(new FileInputStream(tarGzFile));
             TarArchiveInputStream tis = new TarArchiveInputStream(gzis)) {

            ArchiveEntry entry;
            while ((entry = tis.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }

                File outputFile = new File(destDir, entry.getName());
                // 安全检查
                if (!outputFile.getCanonicalPath().startsWith(destDir.getCanonicalPath())) {
                    log.warn("检测到可疑路径，跳过: {}", entry.getName());
                    continue;
                }

                outputFile.getParentFile().mkdirs();

                try (FileOutputStream fos = new FileOutputStream(outputFile)) {
                    byte[] buffer = new byte[8192];
                    int len;
                    while ((len = tis.read(buffer)) > 0) {
                        fos.write(buffer, 0, len);
                    }
                }

                extractedFiles.add(outputFile);
                log.debug("解压文件: {}", outputFile.getAbsolutePath());
            }
        }

        return extractedFiles;
    }

    /**
     * 递归删除目录
     */
    public static void deleteDirectory(File dir) throws IOException {
        if (dir == null || !dir.exists()) {
            return;
        }

        File[] files = dir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    deleteDirectory(file);
                } else {
                    if (!file.delete()) {
                        log.warn("删除文件失败: {}", file.getAbsolutePath());
                    }
                }
            }
        }

        if (!dir.delete()) {
            log.warn("删除目录失败: {}", dir.getAbsolutePath());
        }
    }

    /**
     * 压缩包类型枚举
     */
    public enum ArchiveType {
        ZIP,
        TAR,
        TAR_GZ
    }
}

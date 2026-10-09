package com.richuang.os.module.infra.service.file;

import com.richuang.os.framework.common.pojo.PageResult;
import com.richuang.os.module.infra.controller.admin.file.vo.file.FileCreateReqVO;
import com.richuang.os.module.infra.controller.admin.file.vo.file.FilePageReqVO;
import com.richuang.os.module.infra.controller.admin.file.vo.file.FilePresignedUrlRespVO;
import com.richuang.os.module.infra.dal.dataobject.file.FileDO;

import jakarta.validation.constraints.NotEmpty;

import java.io.InputStream;
import java.util.List;

/**
 * 文件 Service 接口
 *
 * @author os
 */
public interface FileService {

    /**
     * 获得文件分页
     *
     * @param pageReqVO 分页查询
     * @return 文件分页
     */
    PageResult<FileDO> getFilePage(FilePageReqVO pageReqVO);

    /**
     * 保存文件，并返回文件的访问路径
     *
     * @param content 文件内容
     * @param name 文件名称，允许空
     * @param directory 目录，允许空
     * @param type 文件的 MIME 类型，允许空
     * @return 文件路径
     */
    FileDO createFile(
            @NotEmpty(message = "文件内容不能为空") byte[] content,
            String name,
            String directory,
            String type);

    /**
     * 保存文件到指定配置的存储，并返回文件记录
     *
     * @param configId 文件配置编号（对应 {@code infra_file_config.id}）
     * @param content 文件内容
     * @param name 文件名称，允许空
     * @param directory 目录，允许空
     * @param type 文件的 MIME 类型，允许空
     * @return 文件记录
     */
    FileDO createFile(
            Long configId,
            @NotEmpty(message = "文件内容不能为空") byte[] content,
            String name,
            String directory,
            String type);

    /**
     * 保存受保护文件，并返回文件记录
     *
     * <p>受保护文件的读取只能经由业务模块鉴权后进行，通用文件接口（按路径、按编号）一律拒绝访问。 使用主配置存储，与普通上传一致。
     *
     * @param content 文件内容
     * @param name 文件名称，允许空
     * @param directory 目录，允许空
     * @param type 文件的 MIME 类型，允许空
     * @return 文件记录
     */
    FileDO createProtectedFile(
            @NotEmpty(message = "文件内容不能为空") byte[] content,
            String name,
            String directory,
            String type);

    /**
     * 流式保存受保护文件，并返回文件记录
     *
     * <p>用于大文件上传：内容按流写入存储器，不在内存中整份缓冲。 文件名必填（流式无法按内容回退命名），MIME 类型为空时按文件名推断。
     *
     * @param content 文件内容流，由调用方负责关闭
     * @param contentLength 文件长度
     * @param name 文件名称
     * @param directory 目录，允许空
     * @param type 文件的 MIME 类型，允许空
     * @return 文件记录
     */
    FileDO createProtectedFile(
            InputStream content,
            long contentLength,
            @NotEmpty(message = "文件名称不能为空") String name,
            String directory,
            String type)
            throws Exception;

    /** 使用指定存储配置流式保存受保护文件。 */
    FileDO createProtectedFile(
            Long configId,
            InputStream content,
            long contentLength,
            @NotEmpty(message = "文件名称不能为空") String name,
            String directory,
            String type)
            throws Exception;

    /**
     * 判断存储路径下是否存在受保护文件
     *
     * @param configId 配置编号
     * @param path 文件路径
     * @return 是否存在受保护文件
     */
    boolean isProtectedFile(Long configId, String path);

    /**
     * 生成文件预签名地址信息，用于上传
     *
     * @param name 文件名
     * @param directory 目录
     * @return 预签名地址信息
     */
    FilePresignedUrlRespVO presignPutUrl(
            @NotEmpty(message = "文件名不能为空") String name, String directory);

    /**
     * 生成文件预签名地址信息，用于读取
     *
     * @param url 完整的文件访问地址
     * @param expirationSeconds 访问有效期，单位秒
     * @return 文件预签名地址
     */
    String presignGetUrl(String url, Integer expirationSeconds);

    /**
     * 使用指定配置生成文件预签名地址信息，用于读取
     *
     * @param configId 文件配置编号（对应 {@code infra_file_config.id}）
     * @param url 完整的文件访问地址
     * @param expirationSeconds 访问有效期，单位秒
     * @return 文件预签名地址
     */
    String presignGetUrl(Long configId, String url, Integer expirationSeconds);

    /**
     * 创建文件
     *
     * @param createReqVO 创建信息
     * @return 编号
     */
    Long createFile(FileCreateReqVO createReqVO);

    FileDO getFile(Long id);

    List<FileDO> getFiles(List<Long> ids);

    /**
     * 删除文件
     *
     * @param id 编号
     */
    void deleteFile(Long id) throws Exception;

    /**
     * 批量删除文件
     *
     * @param ids 编号列表
     */
    void deleteFileList(List<Long> ids) throws Exception;

    /**
     * 获得文件内容
     *
     * @param configId 配置编号
     * @param path 文件路径
     * @return 文件内容
     */
    byte[] getFileContent(Long configId, String path) throws Exception;

    /**
     * 流式获得文件内容，从指定偏移量开始读取
     *
     * @param configId 配置编号
     * @param path 文件路径
     * @param offset 起始偏移量，0 表示从头读取
     * @return 文件内容流；文件不存在时返回 null，调用方负责关闭
     */
    InputStream getFileContentStream(Long configId, String path, long offset) throws Exception;

    /**
     * 获得文件内容的长度
     *
     * @param configId 配置编号
     * @param path 文件路径
     * @return 文件长度；文件不存在时返回 null
     */
    Long getFileContentLength(Long configId, String path) throws Exception;
}

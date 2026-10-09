package com.lingan.ucp.nocode.web;

import static com.lingan.ucp.nocode.api.NocodeErrorCodes.invalid;

import cn.hutool.core.text.csv.CsvUtil;

import com.lingan.ucp.framework.excel.core.util.ExcelUtils;
import com.lingan.ucp.nocode.api.*;
import com.lingan.ucp.nocode.api.DataCenter.*;
import com.lingan.ucp.nocode.api.ObjectImports.Error;
import com.lingan.ucp.nocode.api.ObjectImports.Preview;
import com.lingan.ucp.nocode.controller.admin.vo.ObjectImportRow;
import com.lingan.ucp.nocode.enums.*;
import com.lingan.ucp.nocode.metadata.service.object.DraftValidator;
import com.lingan.ucp.nocode.metadata.service.object.ObjectDesignService;

import jakarta.annotation.Resource;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** 结构导入复用底座 Excel/Hutool；只创建对象草稿，不导入业务记录或执行表格公式。 */
@Service
public class ObjectImportService {
    public static final List<String> HEADERS =
            List.of("字段编码", "字段名称", "字段类型", "长度", "精度", "小数位数", "必填", "唯一");
    @Resource private DraftValidator validator;
    @Resource private ObjectDesignService designs;

    public Preview preview(MultipartFile file) {
        if (file == null || file.isEmpty() || file.getSize() > 2 * 1024 * 1024)
            throw invalid("请选择不超过 2 MB 的结构模板");
        String name = Objects.toString(file.getOriginalFilename(), "").toLowerCase(Locale.ROOT);
        try {
            List<ObjectImportRow> rows;
            if (name.endsWith(".xlsx") || name.endsWith(".xls"))
                rows = ExcelUtils.read(file, ObjectImportRow.class, 200);
            else if (name.endsWith(".csv")) rows = csv(file);
            else throw invalid("仅支持 Excel 或 UTF-8 CSV 模板");
            if (rows.isEmpty()) throw invalid("模板没有字段定义");
            var columns = new ArrayList<ImportColumn>();
            var errors = new ArrayList<Error>();
            Set<String> codes = new HashSet<>();
            for (int i = 0; i < rows.size(); i++) {
                var r = rows.get(i);
                try {
                    var candidate =
                            new ImportColumn(
                                    trim(r.getCode()),
                                    trim(r.getName()),
                                    trim(r.getType()).toUpperCase(Locale.ROOT),
                                    integer(r.getLength()),
                                    integer(r.getPrecision()),
                                    integer(r.getScale()),
                                    bool(r.getRequired()),
                                    bool(r.getUnique()));
                    var normalized = normalize(candidate, i);
                    if (!codes.add(normalized.code())) throw invalid("字段编码重复");
                    columns.add(normalized);
                } catch (RuntimeException exception) {
                    errors.add(
                            new Error(
                                    i + 2,
                                    exception
                                                    instanceof
                                                    com.lingan.ucp.framework.common.exception
                                                            .ServiceException
                                            ? exception.getMessage()
                                            : "字段长度、精度或布尔值格式无效"));
                }
            }
            return new Preview(columns, errors);
        } catch (IOException exception) {
            throw invalid("模板读取失败，请检查文件格式");
        }
    }

    private List<ObjectImportRow> csv(MultipartFile file) throws IOException {
        List<cn.hutool.core.text.csv.CsvRow> data;
        try (var reader = new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8)) {
            data = CsvUtil.getReader().read(reader).getRows();
        }
        if (data.isEmpty()) throw invalid("CSV 内容为空");
        var header = new ArrayList<>(data.getFirst().getRawList());
        header.set(0, header.getFirst().replace("\ufeff", ""));
        if (!header.equals(HEADERS)) throw invalid("CSV 表头与模板不一致");
        if (data.size() > 201) throw invalid("结构导入最多支持 200 个字段");
        var result = new ArrayList<ObjectImportRow>();
        for (var values : data.subList(1, data.size())) {
            if (values.size() != 8) throw invalid("CSV 每行必须包含模板中的 8 列");
            ObjectImportRow row = new ObjectImportRow();
            row.setCode(values.get(0));
            row.setName(values.get(1));
            row.setType(values.get(2));
            row.setLength(values.get(3));
            row.setPrecision(values.get(4));
            row.setScale(values.get(5));
            row.setRequired(values.get(6));
            row.setUnique(values.get(7));
            result.add(row);
        }
        return result;
    }

    private String trim(String value) {
        return Objects.toString(value, "").trim();
    }

    private Integer integer(String value) {
        return value == null || value.isBlank() ? null : Integer.valueOf(value.trim());
    }

    private boolean bool(String value) {
        return switch (trim(value).toLowerCase(Locale.ROOT)) {
            case "", "false", "否", "0" -> false;
            case "true", "是", "1" -> true;
            default -> throw invalid("必填/唯一只能填写是、否、true 或 false");
        };
    }

    private ImportColumn normalize(ImportColumn value, int index) {
        if (value == null
                || !FieldTypeEnum.containsCode(value.type())
                || !FieldTypeEnum.fromCode(value.type()).supportsStructureImport())
            throw invalid("导入类型不支持，请使用模板中的基础字段类型；关系和计算字段在设计器中配置");
        var f =
                validator.field(
                        new FieldDefinition(
                                "import-" + index,
                                null,
                                value.code(),
                                value.name(),
                                value.type(),
                                value.length(),
                                value.precision(),
                                value.scale(),
                                value.required(),
                                value.unique(),
                                index),
                        "1");
        return new ImportColumn(
                f.code(),
                f.name(),
                f.type(),
                f.length(),
                f.precision(),
                f.scale(),
                f.required(),
                f.unique());
    }

    /** 确认时重新验证请求内容，不信任预检结果或客户端是否修改过文件。 */
    public Design create(ImportDesign request, long actor) {
        if (request == null
                || request.columns() == null
                || request.columns().isEmpty()
                || request.columns().size() > 200) throw invalid("请导入 1–200 个字段");
        List<FieldDefinition> fields = new ArrayList<>();
        for (int i = 0; i < request.columns().size(); i++) {
            var c = normalize(request.columns().get(i), i);
            fields.add(
                    new FieldDefinition(
                            "import-" + c.code(),
                            null,
                            c.code(),
                            c.name(),
                            c.type(),
                            c.length(),
                            c.precision(),
                            c.scale(),
                            c.required(),
                            c.unique(),
                            i));
        }
        var input =
                new SaveObjectDraft(
                        null,
                        null,
                        request.objectCode(),
                        request.objectName(),
                        null,
                        request.tableName(),
                        "import-" + request.titleColumn(),
                        fields,
                        List.of(),
                        null,
                        request.category());
        return designs.save(
                new SaveDesign(
                        input, Settings.defaults(), Map.of(), List.of(), List.of(), List.of()),
                actor);
    }

    public static ObjectImportRow example() {
        ObjectImportRow row = new ObjectImportRow();
        row.setCode("name");
        row.setName("名称");
        row.setType(FieldTypeEnum.TEXT.getCode());
        row.setLength("200");
        row.setRequired("是");
        row.setUnique("否");
        return row;
    }
}

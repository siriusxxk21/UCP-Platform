//package com.lingan.ucp.common.exception;
//
//import com.lingan.ucp.framework.common.pojo.Result;
//import lombok.extern.slf4j.Slf4j;
//import org.springframework.validation.BindException;
//import org.springframework.web.bind.annotation.ExceptionHandler;
//import org.springframework.web.bind.annotation.RestControllerAdvice;
//
//import java.util.HashMap;
//import java.util.Map;
//
//@Slf4j
//@RestControllerAdvice
//public class GlobalExceptionHandler {
//
//    @ExceptionHandler(BusinessException.class)
//    public Result<Void> handleBusinessException(BusinessException e) {
//        log.warn("业务异常: {}", e.getMessage());
//        return Result.error(e.getCode(), e.getMessage());
//    }
//
//    @ExceptionHandler(TemplateRenderException.class)
//    public Result<Map<String, Object>> handleTemplateRenderException(TemplateRenderException e) {
//        log.warn("模板渲染异常: {}", e.getMessage());
//
//        Map<String, Object> errorData = new HashMap<>();
//        errorData.put("templateName", e.getTemplateName());
//        errorData.put("templateType", e.getTemplateType());
//        errorData.put("errorLocation", e.getErrorLocation());
//        errorData.put("errorDetail", e.getErrorDetail());
//        errorData.put("dataModel", e.getDataModel());
//
//        return Result.error(500, e.getMessage(), errorData);
//    }
//
//    @ExceptionHandler(BindException.class)
//    public Result<Void> handleBindException(BindException e) {
//        String message = e.getBindingResult().getAllErrors().get(0).getDefaultMessage();
//        log.warn("参数校验失败: {}", message);
//        return Result.error(400, message);
//    }
//
//    @ExceptionHandler(RuntimeException.class)
//    public Result<Void> handleRuntimeException(RuntimeException e) {
//        log.warn("运行时异常: {}", e.getMessage());
//        // 返回具体的错误信息给前端
//        return Result.error(400, e.getMessage());
//    }
//
//    @ExceptionHandler(Exception.class)
//    public Result<Void> handleException(Exception e) {
//        log.error("系统异常", e);
//        return Result.error("系统繁忙，请稍后重试");
//    }
//}

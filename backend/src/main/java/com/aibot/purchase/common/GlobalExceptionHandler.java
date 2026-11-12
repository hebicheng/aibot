package com.aibot.purchase.common;

import jakarta.servlet.http.HttpServletRequest;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {
  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  @ExceptionHandler(ApiException.class)
  public ResponseEntity<ApiResponse<Void>> handleApi(ApiException ex) {
    return ResponseEntity.status(ex.status()).body(ApiResponse.error(ex.getMessage()));
  }

  @ExceptionHandler({MethodArgumentNotValidException.class, BindException.class})
  public ResponseEntity<ApiResponse<Void>> handleValidation(Exception ex) {
    String message;
    if (ex instanceof MethodArgumentNotValidException validationException) {
      message = validationException.getBindingResult().getFieldErrors().stream()
          .map(error -> error.getField() + error.getDefaultMessage())
          .collect(Collectors.joining("；"));
    } else if (ex instanceof BindException bindException) {
      message = bindException.getBindingResult().getFieldErrors().stream()
          .map(error -> error.getField() + error.getDefaultMessage())
          .collect(Collectors.joining("；"));
    } else {
      message = "参数校验失败";
    }
    return ResponseEntity.badRequest().body(ApiResponse.error(message));
  }

  /**
   * 未知路径（手误打错、或过期客户端调旧接口）：返回 404，日志降为 WARN 且不打堆栈。
   *
   * <p>2026-11-12：排障时手误请求了 {@code /api/ledger/columns}（真实路径是
   * {@code /api/reports/ledger/columns}），Spring 抛 NoResourceFoundException 被下面的兜底
   * 捕获成 ERROR + 完整堆栈，看着像服务炸了。路径不存在不是服务异常，不该按 500 处理。
   */
  @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
  public ResponseEntity<ApiResponse<Void>> handleNoResource(
      HttpServletRequest request) {
    log.warn("请求的路径不存在：{}", request.getRequestURI());
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .body(ApiResponse.error("接口不存在：" + request.getRequestURI()));
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception ex, HttpServletRequest request) {
    log.error("未处理异常 uri={}", request.getRequestURI(), ex);
    if (request.getRequestURI().contains("/api/")) {
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
          .body(ApiResponse.error("服务暂时不可用，请稍后重试"));
    }
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
        .body(ApiResponse.error(ex.getMessage()));
  }
}

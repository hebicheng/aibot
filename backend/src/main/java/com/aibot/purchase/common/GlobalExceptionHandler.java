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

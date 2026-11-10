package com.aibot.purchase.controller;

import com.aibot.purchase.common.ApiResponse;
import com.aibot.purchase.common.CurrentUser;
import com.aibot.purchase.dto.BusinessDtos.ReportDto;
import com.aibot.purchase.service.BusinessQueryService;
import com.aibot.purchase.service.LedgerExportService;
import com.aibot.purchase.service.LedgerTableSchema;
import com.aibot.purchase.service.ReconciliationExportService;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/reports")
public class ReportController {
  private final BusinessQueryService businessQueryService;
  private final LedgerExportService ledgerExportService;
  private final ReconciliationExportService reconciliationExportService;

  public ReportController(
      BusinessQueryService businessQueryService,
      LedgerExportService ledgerExportService,
      ReconciliationExportService reconciliationExportService
  ) {
    this.businessQueryService = businessQueryService;
    this.ledgerExportService = ledgerExportService;
    this.reconciliationExportService = reconciliationExportService;
  }

  @GetMapping("/finance")
  public ApiResponse<ReportDto> finance(
      @RequestParam(defaultValue = "project") String scope,
      @RequestParam(required = false) String projectId,
      @RequestParam(required = false) String batchId
  ) {
    return ApiResponse.ok(businessQueryService.report(CurrentUser.get().id(), scope, projectId, batchId));
  }

  /**
   * 台账表头定义（唯一来源 LedgerTableSchema）：web 页面按此渲染三层表头，
   * 与导出 xlsx 的表头保持同一结构，禁止在前端硬编码第二份。
   */
  @GetMapping("/ledger/columns")
  public ApiResponse<Object> ledgerColumns() {
    return ApiResponse.ok(LedgerTableSchema.HEADER);
  }

  /**
   * 台账导出：按页面表格结构生成 xlsx（三层表头、合并单元格、金额与状态样式）。
   * web 与小程序共用同一接口，保证两端导出结果一致。
   * batchId 传入时只导出该批次（web 端「以当前批次为准」）；不传保持项目级导出。
   */
  @GetMapping("/ledger/export")
  public ResponseEntity<byte[]> exportLedger(
      @RequestParam(required = false) String projectId,
      @RequestParam(required = false) String batchId) {
    byte[] content = ledgerExportService.export(CurrentUser.get().id(), projectId, batchId);
    String fileName = ledgerExportService.fileName();
    String encoded;
    try {
      encoded = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replaceAll("\\+", "%20");
    } catch (Exception ex) {
      encoded = "ledger.xlsx";
    }
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
        .header(HttpHeaders.CONTENT_DISPOSITION,
            "attachment; filename*=UTF-8''" + encoded)
        .body(content);
  }

  /** 对账单卡片实时汇总：项目/客户信息 + 指定日期范围的送货单张数与锁定销售额 */
  @GetMapping("/reconciliation/summary")
  public ApiResponse<Map<String, Object>> reconciliationSummary(
      @RequestParam String projectId,
      @RequestParam String start,
      @RequestParam String end
  ) {
    LocalDate startDate;
    LocalDate endDate;
    try {
      startDate = LocalDate.parse(start);
      endDate = LocalDate.parse(end);
    } catch (DateTimeParseException ex) {
      throw new IllegalArgumentException("日期格式不正确，应为 yyyy-MM-dd");
    }
    return ApiResponse.ok(reconciliationExportService.summaryView(
        CurrentUser.get().id(), projectId, startDate, endDate));
  }

  /**
   * 对账单导出（销售侧，模板渲染）：日期范围内送货单明细 + 项目累计（≤ 范围结束日，含本次）+ 回款累计。
   * 一次导出 = 一个日期范围一个 sheet。
   */
  @GetMapping("/reconciliation/export")
  public ResponseEntity<byte[]> exportReconciliation(
      @RequestParam String projectId,
      @RequestParam String start,
      @RequestParam String end
  ) {
    LocalDate startDate;
    LocalDate endDate;
    try {
      startDate = LocalDate.parse(start);
      endDate = LocalDate.parse(end);
    } catch (DateTimeParseException ex) {
      throw new IllegalArgumentException("日期格式不正确，应为 yyyy-MM-dd");
    }
    if (startDate.isAfter(endDate)) {
      throw new IllegalArgumentException("开始日期不能晚于结束日期");
    }
    String userId = CurrentUser.get().id();
    byte[] content = reconciliationExportService.export(userId, projectId, startDate, endDate);
    String fileName = reconciliationExportService.fileName(userId, projectId, startDate, endDate);
    String encoded;
    try {
      encoded = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replaceAll("\\+", "%20");
    } catch (Exception ex) {
      encoded = "reconciliation.xlsx";
    }
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
        .header(HttpHeaders.CONTENT_DISPOSITION,
            "attachment; filename*=UTF-8''" + encoded)
        .body(content);
  }
}

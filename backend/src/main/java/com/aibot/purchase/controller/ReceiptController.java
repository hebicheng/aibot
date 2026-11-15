package com.aibot.purchase.controller;

import com.aibot.purchase.common.ApiResponse;
import com.aibot.purchase.common.CurrentUser;
import com.aibot.purchase.dto.BusinessDtos.ProjectReceiptOverviewDto;
import com.aibot.purchase.dto.BusinessDtos.ReceiptDto;
import com.aibot.purchase.dto.BusinessDtos.ReceiptSaveRequest;
import com.aibot.purchase.dto.BusinessDtos.ReceiptSuggestionDto;
import com.aibot.purchase.service.ReceiptService;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 项目收款：一笔收款关联多个批次（2026-11-15）。 */
@RestController
@RequestMapping("/api/projects/{projectId}/receipts")
public class ReceiptController {
  private final ReceiptService receiptService;

  public ReceiptController(ReceiptService receiptService) {
    this.receiptService = receiptService;
  }

  @GetMapping
  public ApiResponse<ProjectReceiptOverviewDto> overview(@PathVariable String projectId) {
    return ApiResponse.ok(receiptService.overview(CurrentUser.get().id(), projectId));
  }

  /** 自动分摊建议：不传 batchIds 时按销售顺序自动勾选未收齐的批次。 */
  @GetMapping("/suggest")
  public ApiResponse<ReceiptSuggestionDto> suggest(
      @PathVariable String projectId,
      @RequestParam BigDecimal amount,
      @RequestParam(required = false) List<String> batchIds,
      @RequestParam(required = false) String excludeReceiptId
  ) {
    return ApiResponse.ok(receiptService.suggest(
        CurrentUser.get().id(), projectId, amount, batchIds, excludeReceiptId));
  }

  @PostMapping
  public ApiResponse<ReceiptDto> create(
      @PathVariable String projectId, @Valid @RequestBody ReceiptSaveRequest request) {
    return ApiResponse.ok(receiptService.create(CurrentUser.get().id(), projectId, request));
  }

  @PutMapping("/{receiptId}")
  public ApiResponse<ReceiptDto> update(
      @PathVariable String projectId,
      @PathVariable String receiptId,
      @Valid @RequestBody ReceiptSaveRequest request) {
    return ApiResponse.ok(receiptService.update(CurrentUser.get().id(), projectId, receiptId, request));
  }

  @DeleteMapping("/{receiptId}")
  public ApiResponse<Void> delete(@PathVariable String projectId, @PathVariable String receiptId) {
    receiptService.delete(CurrentUser.get().id(), projectId, receiptId);
    return ApiResponse.ok();
  }
}

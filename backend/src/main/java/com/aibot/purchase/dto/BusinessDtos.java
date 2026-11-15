package com.aibot.purchase.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public final class BusinessDtos {
  private BusinessDtos() {
  }

  public record ProjectDto(
      String id,
      String name,
      String customerName,
      String status,
      Integer progress,
      /** 采购合同总额：与台账同源（含采购运费成本），不再单独剔运费，避免项目页与台账算出两个毛利率 */
      BigDecimal purchaseAmount,
      /** 总付款：台账行 payment 单据累计实付 */
      BigDecimal paidAmount,
      /** 总收票：台账行进项发票累计 */
      BigDecimal invoiceAmount,
      /** 未付款 = 采购合同总额 − 总付款（下限 0，与台账 unpaid_amount 一致） */
      BigDecimal unpaidPurchase,
      /** 未收发票 = 总付款 − 总收票：付了钱还没拿到票，可能为负（预开票） */
      BigDecimal uninvoicedPurchase,
      /** 销售合同总额：与台账同源（含客户承担的送货运费） */
      BigDecimal salesAmount,
      /** 总开票：台账行销项发票累计 */
      BigDecimal salesInvoiceAmount,
      /** 总收款：台账行 receipt 单据累计回款 */
      BigDecimal receivedAmount,
      /** 应收款 = 销售合同总额 − 总收款（下限 0，与台账 outstanding 一致） */
      BigDecimal outstanding,
      /** 未开票 = 销售合同总额 − 总开票，可能为负（预开票） */
      BigDecimal uninvoicedSales,
      BigDecimal profitAmount,
      BigDecimal marginRate,
      /** 项目级业务默认口径（按单据类型 scope 分组），批次没单独设置的字段用它 */
      Map<String, Object> businessHints,
      Instant updatedAt,
      Integer batchCount
  ) {
  }

  public record ProjectCreateRequest(
      @NotBlank String name,
      String customerName
  ) {
  }

  public record ProjectUpdateRequest(
      String name,
      String customerName
  ) {
  }

  public record BatchDto(
      String id,
      String projectId,
      String projectName,
      String name,
      LocalDate businessDate,
      String materialSummary,
      String status,
      Integer progress,
      BigDecimal purchaseAmount,
      BigDecimal salesAmount,
      BigDecimal profitAmount,
      BigDecimal marginRate,
      String remark,
      Map<String, Object> businessHints,
      Instant updatedAt,
      /** 是否已对账（人工勾选） */
      Boolean reconciled,
      /** 已收款 = Σ 项目收款在本批次上的分摊 */
      BigDecimal receivedAmount,
      /** 收款状态：未收款 / 部分收款 / 已收款 */
      String receiptStatus
  ) {
  }

  public record BatchCreateRequest(
      @NotBlank String projectId,
      @NotBlank String name,
      @NotNull LocalDate businessDate,
      @NotBlank String materialSummary,
      String remark
  ) {
  }

  public record BatchUpdateRequest(
      String name,
      String status,
      Integer progress,
      String remark,
      LocalDate businessDate,
      String materialSummary,
      /** 批次级业务默认口径（按单据类型 scope 分组）；null=不修改，空 map=清空 */
      Map<String, Object> businessHints,
      /** 是否已对账；null=不修改 */
      Boolean reconciled
  ) {
  }

  public record MaterialDto(
      String id,
      String batchId,
      String name,
      String spec,
      String unit,
      BigDecimal quantity,
      BigDecimal budgetUnitPrice,
      String selectedSupplier,
      BigDecimal selectedQuote,
      BigDecimal marketPrice
  ) {
  }

  public record MaterialSaveRequest(
      String id,
      @NotBlank String name,
      @NotBlank String spec,
      @NotBlank String unit,
      @NotNull @PositiveOrZero BigDecimal quantity,
      @NotNull @PositiveOrZero BigDecimal budgetUnitPrice,
      String selectedSupplier,
      BigDecimal selectedQuote,
      BigDecimal marketPrice
  ) {
  }

  public record SupplierDto(
      String id,
      String name,
      Integer rating,
      String paymentTerms,
      Integer deliveryDays,
      String riskNote
  ) {
  }

  public record QuoteDto(
      String id,
      String batchId,
      String supplierId,
      String supplierName,
      BigDecimal quotePrice,
      BigDecimal marketPriceSnapshot,
      BigDecimal differenceRate,
      Boolean selected,
      Integer rating,
      String paymentTerms,
      Integer deliveryDays,
      String riskNote
  ) {
  }

  public record FlowEventDto(
      String id,
      String code,
      String title,
      String status,
      String eventTime,
      String note,
      String detail,
      Integer sortOrder
  ) {
  }

  public record FinanceRecordDto(
      String id,
      String batchId,
      String type,
      String target,
      BigDecimal amount,
      String status,
      LocalDate occurredDate
  ) {
  }

  public record FinanceSaveRequest(
      String id,
      @NotBlank String type,
      @NotBlank String target,
      @NotNull @PositiveOrZero BigDecimal amount,
      @NotNull LocalDate occurredDate,
      String status
  ) {
  }

  public record FileRecordDto(
      String id,
      String batchId,
      String name,
      String fileType,
      String url,
      String source,
      Instant createdAt
  ) {
  }

  public record OperationLogDto(
      String id,
      String action,
      String content,
      Instant createdAt
  ) {
  }

  public record ChatMessageDto(
      String id,
      String sender,
      String messageType,
      String businessType,
      String content,
      String fileName,
      String status,
      String projectId,
      String projectName,
      String batchId,
      String batchName,
      Map<String, Object> payload,
      Instant createdAt,
      java.math.BigDecimal creditsUsed
  ) {
  }

  public record ChatSendRequest(
      @NotBlank String text,
      @NotBlank String projectId,
      @NotBlank String batchId
  ) {
  }

  public record ChatClarifyRequest(
      @NotBlank String documentId,
      @NotBlank String answer
  ) {
  }

  public record UploadMockRequest(
      @NotBlank String type,
      @NotBlank String projectId,
      @NotBlank String batchId
  ) {
  }

  public record ContextRequest(
      @NotBlank String projectId,
      @NotBlank String batchId
  ) {
  }

  // 选择供应商时携带的「人工修正后的报价」，字段均可选；为空则沿用 AI 识别的原始 payload。
  public record QuoteEditRequest(
      String supplierName,
      String paymentTerms,
      Integer deliveryDays,
      Integer rating,
      List<QuoteItemEdit> items
  ) {
  }

  public record QuoteItemEdit(
      String materialName,
      BigDecimal quotePrice,
      BigDecimal marketPrice
  ) {
  }

  public record ChatAppendResponse(
      List<ChatMessageDto> appended
  ) {
  }

  public record BatchDetailDto(
      BatchDto batch,
      ProjectDto project,
      List<MaterialDto> materials,
      List<QuoteDto> quotes,
      List<FlowEventDto> flows,
      List<FinanceRecordDto> financeRecords,
      List<FileRecordDto> files
  ) {
  }

  public record DashboardOverviewDto(
      List<KpiDto> kpis,
      List<ProjectDto> projects,
      List<BatchDto> batches,
      List<OperationLogDto> histories
  ) {
  }

  public record KpiDto(String label, String value, String trend) {
  }

  public record ReportDto(
      String scope,
      List<KpiDto> kpis,
      List<ChartPointDto> cashflow,
      List<ChartPointDto> profitTrend,
      List<ChartPointDto> costStructure,
      List<SupplierSummaryDto> suppliers
  ) {
  }

  public record ChartPointDto(String label, BigDecimal value) {
  }

  public record SupplierSummaryDto(String name, BigDecimal amount, Integer onTimeRate, Integer times) {
  }

  // ---------------------------------------------------------------------------
  // 项目收款（2026-11-15）：一笔收款可关联同项目下多个批次
  // ---------------------------------------------------------------------------

  /** 收款关联的某个批次；amount 为空表示由系统按销售顺序自动分摊。 */
  public record ReceiptAllocationInput(
      @NotBlank String batchId,
      BigDecimal amount
  ) {
  }

  public record ReceiptSaveRequest(
      @NotNull LocalDate receiptDate,
      @NotNull @PositiveOrZero BigDecimal amount,
      String payer,
      String remark,
      @NotNull List<ReceiptAllocationInput> allocations
  ) {
  }

  public record ReceiptAllocationDto(
      String batchId,
      String batchName,
      String material,
      BigDecimal amount
  ) {
  }

  public record ReceiptDto(
      String id,
      LocalDate receiptDate,
      BigDecimal amount,
      String payer,
      String remark,
      /** 来源：manual=手动登记 / document=智能助理收款单 */
      String source,
      List<ReceiptAllocationDto> allocations
  ) {
  }

  /** 收款视角下的批次（按销售顺序排列）。 */
  public record ReceiptBatchDto(
      String batchId,
      String batchName,
      LocalDate businessDate,
      String material,
      BigDecimal salesAmount,
      BigDecimal receivedAmount,
      /** 未收 = 销售金额 − 已收（下限 0） */
      BigDecimal remainingAmount,
      String receiptStatus,
      Boolean reconciled
  ) {
  }

  public record MaterialReceiptSummaryDto(
      String material,
      BigDecimal salesAmount,
      BigDecimal receivedAmount,
      /** 应收差额 = 销售金额 − 收款金额（可为负：多收） */
      BigDecimal differenceAmount,
      Integer batchCount
  ) {
  }

  public record ProjectReceiptOverviewDto(
      String projectId,
      /** 应收 = 所有批次销售金额合计 */
      BigDecimal salesAmount,
      /** 收款金额合计 */
      BigDecimal receivedAmount,
      /** 应收差额 = 应收 − 收款（可为负：多收） */
      BigDecimal differenceAmount,
      List<ReceiptDto> receipts,
      List<ReceiptBatchDto> batches,
      List<MaterialReceiptSummaryDto> materials
  ) {
  }

  /** 自动分摊建议：给定金额，按销售顺序勾选批次并预分配。 */
  public record ReceiptSuggestionDto(
      BigDecimal amount,
      List<ReceiptAllocationDto> allocations,
      /** 选中批次全部收齐后仍多出来的钱（> 0 时提示多收） */
      BigDecimal unallocatedAmount
  ) {
  }
}

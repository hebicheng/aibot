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
      Instant updatedAt
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
      Map<String, Object> businessHints
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
}

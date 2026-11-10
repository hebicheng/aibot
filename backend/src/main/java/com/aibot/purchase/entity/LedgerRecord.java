package com.aibot.purchase.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@TableName("ledger_records")
public class LedgerRecord {
  @TableId(type = IdType.ASSIGN_UUID)
  private String id;
  private String projectId;
  private String batchId;
  private String sourceDocumentId;
  private LocalDate purchaseDate;
  private String material;
  private String supplier;
  private BigDecimal purchaseAmount;
  private BigDecimal freight;
  private BigDecimal deliveryAmount;
  private BigDecimal deliveredPackageQuantity;
  private String deliveredPackageUnit;
  private BigDecimal deliveredWeight;
  private String deliveredWeightUnit;
  private BigDecimal requestedAmount;
  private BigDecimal paidAmount;
  private BigDecimal unpaidAmount;
  private BigDecimal invoiceAmount;
  private String taxRate;
  private String salesOrder;
  private BigDecimal salesAmount;
  private LocalDate salesInvoiceDate;
  private BigDecimal salesInvoiceAmount;
  private String salesTaxRate;
  private BigDecimal receivedAmount;
  private BigDecimal outstanding;
  private LocalDate receiptDate;
  private LocalDate requiredReceiptDate;
  private Boolean isMonthlySettlement;
  private BigDecimal grossProfit;
  private BigDecimal grossMargin;
  private String projectManager;
  private String status;
  private LocalDateTime createdAt;
  private LocalDateTime updatedAt;

  // ---- 月结加价利息（时点值，不落库，接口每次现算） ----
  @TableField(exist = false)
  private BigDecimal interest;
  @TableField(exist = false)
  private List<Map<String, Object>> interestDetails;
  @TableField(exist = false)
  private List<Map<String, Object>> receiptEntries;
  @TableField(exist = false)
  private Boolean overdueFlag;
  @TableField(exist = false)
  private Boolean amountCapFlag;
  @TableField(exist = false)
  private String interestSummary;

  // ---- 采购毛利（时点值，不落库，读台账时按批次口径现算；成本含运费） ----
  /** 采购运费成本（元，批次级）：运输单 + 送货单采购运费 + 批次运费口径，累加（2026-11-07 起）。 */
  @TableField(exist = false)
  private BigDecimal purchaseFreightCost;
  /** 采购毛利金额（元，批次级）：估算货款 + 客户承担送货运费 − 采购成本（材料 + 运费）。 */
  @TableField(exist = false)
  private BigDecimal purchaseMarginAmount;
  @TableField(exist = false)
  private BigDecimal purchaseMarginRate;
  /**
   * 采购毛利算不出的原因（批次级，时点值）：no_policy=缺定价口径 / no_supply=无已确认采购单 /
   * no_priced_row=采购明细缺吨数或金额；能算出毛利时为 null。前端据此显示原因而不是空白。
   */
  @TableField(exist = false)
  private String purchaseMarginNote;
  /**
   * 客户承担的送货运费（元，批次级，时点值）：与货款一起构成总收入（毛利率分母），
   * 同时是毛利额加项。与销售运费口径同源，前端在「采购毛利金额」单元格提示里展示。
   */
  @TableField(exist = false)
  private BigDecimal purchaseSalesFreight;
  /**
   * 待采购价明细行数（批次级，时点值）：毛利率口径下没匹配到采购价、金额未计入销售金额的行数。
   * 前端据此在「销售总金额」旁提示，避免对账单静默少算。
   */
  @TableField(exist = false)
  private Integer deliveryCostPendingCount;

  public String getId() { return id; }
  public void setId(String id) { this.id = id; }
  public String getProjectId() { return projectId; }
  public void setProjectId(String projectId) { this.projectId = projectId; }
  public String getBatchId() { return batchId; }
  public void setBatchId(String batchId) { this.batchId = batchId; }
  public String getSourceDocumentId() { return sourceDocumentId; }
  public void setSourceDocumentId(String sourceDocumentId) { this.sourceDocumentId = sourceDocumentId; }
  public LocalDate getPurchaseDate() { return purchaseDate; }
  public void setPurchaseDate(LocalDate purchaseDate) { this.purchaseDate = purchaseDate; }
  public String getMaterial() { return material; }
  public void setMaterial(String material) { this.material = material; }
  public String getSupplier() { return supplier; }
  public void setSupplier(String supplier) { this.supplier = supplier; }
  public BigDecimal getPurchaseAmount() { return purchaseAmount; }
  public void setPurchaseAmount(BigDecimal purchaseAmount) { this.purchaseAmount = purchaseAmount; }
  public BigDecimal getFreight() { return freight; }
  public void setFreight(BigDecimal freight) { this.freight = freight; }
  public BigDecimal getDeliveryAmount() { return deliveryAmount; }
  public void setDeliveryAmount(BigDecimal deliveryAmount) { this.deliveryAmount = deliveryAmount; }
  public BigDecimal getDeliveredPackageQuantity() { return deliveredPackageQuantity; }
  public void setDeliveredPackageQuantity(BigDecimal deliveredPackageQuantity) { this.deliveredPackageQuantity = deliveredPackageQuantity; }
  public String getDeliveredPackageUnit() { return deliveredPackageUnit; }
  public void setDeliveredPackageUnit(String deliveredPackageUnit) { this.deliveredPackageUnit = deliveredPackageUnit; }
  public BigDecimal getDeliveredWeight() { return deliveredWeight; }
  public void setDeliveredWeight(BigDecimal deliveredWeight) { this.deliveredWeight = deliveredWeight; }
  public String getDeliveredWeightUnit() { return deliveredWeightUnit; }
  public void setDeliveredWeightUnit(String deliveredWeightUnit) { this.deliveredWeightUnit = deliveredWeightUnit; }
  public BigDecimal getRequestedAmount() { return requestedAmount; }
  public void setRequestedAmount(BigDecimal requestedAmount) { this.requestedAmount = requestedAmount; }
  public BigDecimal getPaidAmount() { return paidAmount; }
  public void setPaidAmount(BigDecimal paidAmount) { this.paidAmount = paidAmount; }
  public BigDecimal getUnpaidAmount() { return unpaidAmount; }
  public void setUnpaidAmount(BigDecimal unpaidAmount) { this.unpaidAmount = unpaidAmount; }
  public BigDecimal getInvoiceAmount() { return invoiceAmount; }
  public void setInvoiceAmount(BigDecimal invoiceAmount) { this.invoiceAmount = invoiceAmount; }
  public String getTaxRate() { return taxRate; }
  public void setTaxRate(String taxRate) { this.taxRate = taxRate; }
  public String getSalesOrder() { return salesOrder; }
  public void setSalesOrder(String salesOrder) { this.salesOrder = salesOrder; }
  public BigDecimal getSalesAmount() { return salesAmount; }
  public void setSalesAmount(BigDecimal salesAmount) { this.salesAmount = salesAmount; }
  public LocalDate getSalesInvoiceDate() { return salesInvoiceDate; }
  public void setSalesInvoiceDate(LocalDate salesInvoiceDate) { this.salesInvoiceDate = salesInvoiceDate; }
  public BigDecimal getSalesInvoiceAmount() { return salesInvoiceAmount; }
  public void setSalesInvoiceAmount(BigDecimal salesInvoiceAmount) { this.salesInvoiceAmount = salesInvoiceAmount; }
  public String getSalesTaxRate() { return salesTaxRate; }
  public void setSalesTaxRate(String salesTaxRate) { this.salesTaxRate = salesTaxRate; }
  public BigDecimal getReceivedAmount() { return receivedAmount; }
  public void setReceivedAmount(BigDecimal receivedAmount) { this.receivedAmount = receivedAmount; }
  public BigDecimal getOutstanding() { return outstanding; }
  public void setOutstanding(BigDecimal outstanding) { this.outstanding = outstanding; }
  public LocalDate getReceiptDate() { return receiptDate; }
  public void setReceiptDate(LocalDate receiptDate) { this.receiptDate = receiptDate; }
  public LocalDate getRequiredReceiptDate() { return requiredReceiptDate; }
  public void setRequiredReceiptDate(LocalDate requiredReceiptDate) { this.requiredReceiptDate = requiredReceiptDate; }
  public Boolean getIsMonthlySettlement() { return isMonthlySettlement; }
  public void setIsMonthlySettlement(Boolean isMonthlySettlement) { this.isMonthlySettlement = isMonthlySettlement; }
  public BigDecimal getGrossProfit() { return grossProfit; }
  public void setGrossProfit(BigDecimal grossProfit) { this.grossProfit = grossProfit; }
  public BigDecimal getGrossMargin() { return grossMargin; }
  public void setGrossMargin(BigDecimal grossMargin) { this.grossMargin = grossMargin; }
  public String getProjectManager() { return projectManager; }
  public void setProjectManager(String projectManager) { this.projectManager = projectManager; }
  public String getStatus() { return status; }
  public void setStatus(String status) { this.status = status; }
  public LocalDateTime getCreatedAt() { return createdAt; }
  public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
  public LocalDateTime getUpdatedAt() { return updatedAt; }
  public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

  public BigDecimal getInterest() { return interest; }
  public void setInterest(BigDecimal interest) { this.interest = interest; }
  public List<Map<String, Object>> getInterestDetails() { return interestDetails; }
  public void setInterestDetails(List<Map<String, Object>> interestDetails) { this.interestDetails = interestDetails; }
  public List<Map<String, Object>> getReceiptEntries() { return receiptEntries; }
  public void setReceiptEntries(List<Map<String, Object>> receiptEntries) { this.receiptEntries = receiptEntries; }
  public Boolean getOverdueFlag() { return overdueFlag; }
  public void setOverdueFlag(Boolean overdueFlag) { this.overdueFlag = overdueFlag; }
  public Boolean getAmountCapFlag() { return amountCapFlag; }
  public void setAmountCapFlag(Boolean amountCapFlag) { this.amountCapFlag = amountCapFlag; }
  public String getInterestSummary() { return interestSummary; }
  public void setInterestSummary(String interestSummary) { this.interestSummary = interestSummary; }

  public BigDecimal getPurchaseFreightCost() { return purchaseFreightCost; }
  public void setPurchaseFreightCost(BigDecimal purchaseFreightCost) { this.purchaseFreightCost = purchaseFreightCost; }
  public BigDecimal getPurchaseMarginAmount() { return purchaseMarginAmount; }
  public void setPurchaseMarginAmount(BigDecimal purchaseMarginAmount) { this.purchaseMarginAmount = purchaseMarginAmount; }

  public BigDecimal getPurchaseMarginRate() { return purchaseMarginRate; }
  public void setPurchaseMarginRate(BigDecimal purchaseMarginRate) { this.purchaseMarginRate = purchaseMarginRate; }
  public String getPurchaseMarginNote() { return purchaseMarginNote; }
  public void setPurchaseMarginNote(String purchaseMarginNote) { this.purchaseMarginNote = purchaseMarginNote; }
  public BigDecimal getPurchaseSalesFreight() { return purchaseSalesFreight; }
  public void setPurchaseSalesFreight(BigDecimal purchaseSalesFreight) { this.purchaseSalesFreight = purchaseSalesFreight; }
  public Integer getDeliveryCostPendingCount() { return deliveryCostPendingCount; }
  public void setDeliveryCostPendingCount(Integer deliveryCostPendingCount) {
    this.deliveryCostPendingCount = deliveryCostPendingCount;
  }
}


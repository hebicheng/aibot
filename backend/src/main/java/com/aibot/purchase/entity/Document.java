package com.aibot.purchase.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@TableName("documents")
public class Document {
  @TableId(type = IdType.ASSIGN_UUID)
  private String id;
  private String userId;
  private String projectId;
  private String batchId;
  private String docCode;
  private String docType;
  private String supplierName;
  private BigDecimal amount;
  private LocalDate docDate;
  private String status;
  private Integer confidence;
  private String source;
  private String fileName;
  private String fileUrl;
  private String fileObjectKey;
  private String sourceText;
  private String guidanceText;
  private String fileHash;
  private String businessFingerprint;
  private String duplicateOfDocumentId;
  private String duplicateReason;
  private String duplicateDecision;
  private String targetLedgerId;
  private String targetDocumentId;
  private Boolean ledgerApplied;
  private String aiSummary;
  private String structuredData;
  private String sourceMessageId;
  private String remark;
  /** 销售定价方式（送货单）：uplift=网价+上浮（默认）/ margin=网价/(1-毛利率)。null=按默认。 */
  private String pricingMode;
  /** 用户指定的上浮金额（元/吨）；为空用全局配置。 */
  private BigDecimal upliftAmount;
  /** 用户指定的毛利率（百分数，如 2.5 表示 2.5%）；为空默认 2.5。 */
  private BigDecimal marginRate;
  /** 销售运费（元，选填）：客户承担的运费，进台账销售总金额（锁定货款+销售运费）。 */
  private BigDecimal salesFreight;
  private LocalDateTime createdAt;
  private LocalDateTime updatedAt;
  /** 软删除标记：@TableLogic 使所有查询自动过滤已删单据，deleteById 转为置 1（对齐 Project/Batch 模式）。 */
  @TableLogic
  private Integer deleted;

  public String getId() { return id; }
  public void setId(String id) { this.id = id; }
  public String getUserId() { return userId; }
  public void setUserId(String userId) { this.userId = userId; }
  public String getProjectId() { return projectId; }
  public void setProjectId(String projectId) { this.projectId = projectId; }
  public String getBatchId() { return batchId; }
  public void setBatchId(String batchId) { this.batchId = batchId; }
  public String getDocCode() { return docCode; }
  public void setDocCode(String docCode) { this.docCode = docCode; }
  public String getDocType() { return docType; }
  public void setDocType(String docType) { this.docType = docType; }
  public String getSupplierName() { return supplierName; }
  public void setSupplierName(String supplierName) { this.supplierName = supplierName; }
  public BigDecimal getAmount() { return amount; }
  public void setAmount(BigDecimal amount) { this.amount = amount; }
  public LocalDate getDocDate() { return docDate; }
  public void setDocDate(LocalDate docDate) { this.docDate = docDate; }
  public String getStatus() { return status; }
  public void setStatus(String status) { this.status = status; }
  public Integer getConfidence() { return confidence; }
  public void setConfidence(Integer confidence) { this.confidence = confidence; }
  public String getSource() { return source; }
  public void setSource(String source) { this.source = source; }
  public String getFileName() { return fileName; }
  public void setFileName(String fileName) { this.fileName = fileName; }
  public String getFileUrl() { return fileUrl; }
  public void setFileUrl(String fileUrl) { this.fileUrl = fileUrl; }
  public String getFileObjectKey() { return fileObjectKey; }
  public void setFileObjectKey(String fileObjectKey) { this.fileObjectKey = fileObjectKey; }
  public String getSourceText() { return sourceText; }
  public void setSourceText(String sourceText) { this.sourceText = sourceText; }
  public String getGuidanceText() { return guidanceText; }
  public void setGuidanceText(String guidanceText) { this.guidanceText = guidanceText; }
  public String getFileHash() { return fileHash; }
  public void setFileHash(String fileHash) { this.fileHash = fileHash; }
  public String getBusinessFingerprint() { return businessFingerprint; }
  public void setBusinessFingerprint(String businessFingerprint) { this.businessFingerprint = businessFingerprint; }
  public String getDuplicateOfDocumentId() { return duplicateOfDocumentId; }
  public void setDuplicateOfDocumentId(String duplicateOfDocumentId) { this.duplicateOfDocumentId = duplicateOfDocumentId; }
  public String getDuplicateReason() { return duplicateReason; }
  public void setDuplicateReason(String duplicateReason) { this.duplicateReason = duplicateReason; }
  public String getDuplicateDecision() { return duplicateDecision; }
  public void setDuplicateDecision(String duplicateDecision) { this.duplicateDecision = duplicateDecision; }
  public String getTargetLedgerId() { return targetLedgerId; }
  public void setTargetLedgerId(String targetLedgerId) { this.targetLedgerId = targetLedgerId; }
  public String getTargetDocumentId() { return targetDocumentId; }
  public void setTargetDocumentId(String targetDocumentId) { this.targetDocumentId = targetDocumentId; }
  public Boolean getLedgerApplied() { return ledgerApplied; }
  public void setLedgerApplied(Boolean ledgerApplied) { this.ledgerApplied = ledgerApplied; }
  public String getAiSummary() { return aiSummary; }
  public void setAiSummary(String aiSummary) { this.aiSummary = aiSummary; }
  public String getStructuredData() { return structuredData; }
  public void setStructuredData(String structuredData) { this.structuredData = structuredData; }
  public String getSourceMessageId() { return sourceMessageId; }
  public void setSourceMessageId(String sourceMessageId) { this.sourceMessageId = sourceMessageId; }
  public String getRemark() { return remark; }
  public void setRemark(String remark) { this.remark = remark; }
  public String getPricingMode() { return pricingMode; }
  public void setPricingMode(String pricingMode) { this.pricingMode = pricingMode; }
  public BigDecimal getUpliftAmount() { return upliftAmount; }
  public void setUpliftAmount(BigDecimal upliftAmount) { this.upliftAmount = upliftAmount; }
  public BigDecimal getMarginRate() { return marginRate; }
  public void setMarginRate(BigDecimal marginRate) { this.marginRate = marginRate; }
  public BigDecimal getSalesFreight() { return salesFreight; }
  public void setSalesFreight(BigDecimal salesFreight) { this.salesFreight = salesFreight; }
  public LocalDateTime getCreatedAt() { return createdAt; }
  public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
  public LocalDateTime getUpdatedAt() { return updatedAt; }
  public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
  public Integer getDeleted() { return deleted; }
  public void setDeleted(Integer deleted) { this.deleted = deleted; }
}

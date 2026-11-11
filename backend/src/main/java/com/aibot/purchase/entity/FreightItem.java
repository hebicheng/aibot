package com.aibot.purchase.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 批次运费明细：一行一笔。
 *
 * <p>四类来源并列存在、计算时累加（2026-11-11 定）：口径（按吨/按趟）、单独导入的运输单、
 * 单据上明确写的运费。表内容由 {@code FreightService.rebuild(batchId)} 幂等重建。
 */
@TableName("freight_items")
public class FreightItem {
  @TableId(type = IdType.ASSIGN_UUID)
  private String id;
  private String projectId;
  private String batchId;
  /** purchase=采购运费（我方成本） / sales=销售运费（客户承担，入销售额）。 */
  private String side;
  /** policy_ton / policy_trip / transport_note / document_stated。 */
  private String source;
  private String sourceDocumentId;
  private BigDecimal tons;
  private BigDecimal unitPrice;
  private BigDecimal amount;
  private String note;
  /** 承运单位：运输单取往来单位、送货单取 structuredData.carrierName，可人工覆盖。 */
  private String carrierName;
  /** true=人工改过承运单位；重建运费行时据此保住人工值。 */
  private Boolean carrierManual;
  private LocalDateTime createdAt;
  private LocalDateTime updatedAt;

  public String getId() { return id; }
  public void setId(String id) { this.id = id; }

  public String getProjectId() { return projectId; }
  public void setProjectId(String projectId) { this.projectId = projectId; }

  public String getBatchId() { return batchId; }
  public void setBatchId(String batchId) { this.batchId = batchId; }

  public String getSide() { return side; }
  public void setSide(String side) { this.side = side; }

  public String getSource() { return source; }
  public void setSource(String source) { this.source = source; }

  public String getSourceDocumentId() { return sourceDocumentId; }
  public void setSourceDocumentId(String sourceDocumentId) {
    this.sourceDocumentId = sourceDocumentId;
  }

  public BigDecimal getTons() { return tons; }
  public void setTons(BigDecimal tons) { this.tons = tons; }

  public BigDecimal getUnitPrice() { return unitPrice; }
  public void setUnitPrice(BigDecimal unitPrice) { this.unitPrice = unitPrice; }

  public BigDecimal getAmount() { return amount; }
  public void setAmount(BigDecimal amount) { this.amount = amount; }

  public String getNote() { return note; }
  public void setNote(String note) { this.note = note; }

  public String getCarrierName() { return carrierName; }
  public void setCarrierName(String carrierName) { this.carrierName = carrierName; }

  public Boolean getCarrierManual() { return carrierManual; }
  public void setCarrierManual(Boolean carrierManual) { this.carrierManual = carrierManual; }

  public LocalDateTime getCreatedAt() { return createdAt; }
  public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

  public LocalDateTime getUpdatedAt() { return updatedAt; }
  public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}

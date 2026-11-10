package com.aibot.purchase.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;

@TableName("document_items")
public class DocumentItem {
  @TableId(type = IdType.ASSIGN_UUID)
  private String id;
  private String documentId;
  private String materialName;
  private String spec;
  private String origin;
  // 材料层归属（V22/V25，2026-11-04 接线）：归并到的批次材料 + 归并质量/来源
  private String materialId;
  private String materialMatchLevel;
  private String materialMatchSource;
  private String lengthM;
  private String measureMethod;
  private BigDecimal packageQuantity;
  private String packageUnit;
  private BigDecimal unitWeight;
  private BigDecimal totalWeight;
  private String weightUnit;
  private BigDecimal pricingQuantity;
  private String pricingUnit;
  private String unit;
  private BigDecimal quantity;
  private BigDecimal unitPrice;
  private BigDecimal netPrice;
  /** 人工销售单价（元/计价单位），NULL=按批次默认口径自动推算。 */
  private BigDecimal salesUnitPrice;
  private BigDecimal amount;
  private String remark;
  private Integer sortOrder;

  public String getId() { return id; }
  public void setId(String id) { this.id = id; }
  public String getDocumentId() { return documentId; }
  public void setDocumentId(String documentId) { this.documentId = documentId; }
  public String getMaterialName() { return materialName; }
  public void setMaterialName(String materialName) { this.materialName = materialName; }
  public String getSpec() { return spec; }
  public void setSpec(String spec) { this.spec = spec; }
  public String getOrigin() { return origin; }
  public void setOrigin(String origin) { this.origin = origin; }
  public BigDecimal getPackageQuantity() { return packageQuantity; }
  public void setPackageQuantity(BigDecimal packageQuantity) { this.packageQuantity = packageQuantity; }
  public String getPackageUnit() { return packageUnit; }
  public void setPackageUnit(String packageUnit) { this.packageUnit = packageUnit; }
  public BigDecimal getUnitWeight() { return unitWeight; }
  public void setUnitWeight(BigDecimal unitWeight) { this.unitWeight = unitWeight; }
  public BigDecimal getTotalWeight() { return totalWeight; }
  public void setTotalWeight(BigDecimal totalWeight) { this.totalWeight = totalWeight; }
  public String getWeightUnit() { return weightUnit; }
  public void setWeightUnit(String weightUnit) { this.weightUnit = weightUnit; }
  public BigDecimal getPricingQuantity() { return pricingQuantity; }
  public void setPricingQuantity(BigDecimal pricingQuantity) { this.pricingQuantity = pricingQuantity; }
  public String getPricingUnit() { return pricingUnit; }
  public void setPricingUnit(String pricingUnit) { this.pricingUnit = pricingUnit; }
  public String getUnit() { return unit; }
  public void setUnit(String unit) { this.unit = unit; }
  public BigDecimal getQuantity() { return quantity; }
  public void setQuantity(BigDecimal quantity) { this.quantity = quantity; }
  public BigDecimal getUnitPrice() { return unitPrice; }
  public void setUnitPrice(BigDecimal unitPrice) { this.unitPrice = unitPrice; }
  public BigDecimal getNetPrice() { return netPrice; }
  public void setNetPrice(BigDecimal netPrice) { this.netPrice = netPrice; }
  public BigDecimal getSalesUnitPrice() { return salesUnitPrice; }
  public void setSalesUnitPrice(BigDecimal salesUnitPrice) { this.salesUnitPrice = salesUnitPrice; }
  public BigDecimal getAmount() { return amount; }
  public void setAmount(BigDecimal amount) { this.amount = amount; }
  public String getRemark() { return remark; }
  public void setRemark(String remark) { this.remark = remark; }
  public Integer getSortOrder() { return sortOrder; }
  public void setSortOrder(Integer sortOrder) { this.sortOrder = sortOrder; }
  public String getMaterialId() { return materialId; }
  public void setMaterialId(String materialId) { this.materialId = materialId; }
  public String getMaterialMatchLevel() { return materialMatchLevel; }
  public void setMaterialMatchLevel(String materialMatchLevel) { this.materialMatchLevel = materialMatchLevel; }
  public String getMaterialMatchSource() { return materialMatchSource; }
  public void setMaterialMatchSource(String materialMatchSource) { this.materialMatchSource = materialMatchSource; }
  public String getLengthM() { return lengthM; }
  public void setLengthM(String lengthM) { this.lengthM = lengthM; }
  public String getMeasureMethod() { return measureMethod; }
  public void setMeasureMethod(String measureMethod) { this.measureMethod = measureMethod; }
}

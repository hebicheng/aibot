package com.aibot.purchase.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("materials")
public class Material {
  @TableId(type = IdType.ASSIGN_UUID)
  private String id;
  private String batchId;
  private String sourceDocumentId;
  private String name;
  private String spec;
  private String unit;
  private BigDecimal quantity;
  private BigDecimal budgetUnitPrice;
  private String selectedSupplier;
  private BigDecimal selectedQuote;
  private BigDecimal marketPrice;
  private LocalDateTime createdAt;
  private LocalDateTime updatedAt;
}

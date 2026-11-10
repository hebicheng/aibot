package com.aibot.purchase.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("supplier_quotes")
public class SupplierQuote {
  @TableId(type = IdType.ASSIGN_UUID)
  private String id;
  private String batchId;
  private String materialId;
  private String supplierId;
  private BigDecimal quotePrice;
  private BigDecimal marketPriceSnapshot;
  private BigDecimal differenceRate;
  private Boolean selected;
  private LocalDateTime createdAt;
}

package com.aibot.purchase.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("material_market_mappings")
public class MaterialMarketMapping {
  @TableId(type = IdType.ASSIGN_UUID)
  private String id;
  private String materialId;
  private String batchId;
  private String productCode;
  private String specName;
  private String source;
  private String origin;
  private String city;
  private String matchMethod;
  private BigDecimal confidence;
  private String status;
  private LocalDateTime matchedAt;
  private String aiRaw;
  private LocalDateTime createdAt;
  private LocalDateTime updatedAt;
}

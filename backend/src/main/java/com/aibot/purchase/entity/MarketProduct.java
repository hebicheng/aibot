package com.aibot.purchase.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("market_products")
public class MarketProduct {
  @TableId(type = IdType.ASSIGN_UUID)
  private String id;
  private String code;
  private String source;
  private String category;
  private String name;
  private String sourceUrl;
  private Boolean isNav;
  private Integer sortOrder;
  private Boolean enabled;
  private LocalDateTime createdAt;
  private LocalDateTime updatedAt;
}

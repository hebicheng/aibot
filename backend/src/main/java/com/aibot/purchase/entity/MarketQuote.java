package com.aibot.purchase.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("market_quotes")
public class MarketQuote {
  @TableId(type = IdType.ASSIGN_UUID)
  private String id;
  private LocalDate quoteDate;
  private String productCode;
  private String source;
  private String productName;
  private String specName;
  private String spec;
  private String grade;
  private String origin;
  private String city;
  private BigDecimal price;
  private BigDecimal priceChange;
  private String detailUrl;
  private LocalDateTime capturedAt;
}

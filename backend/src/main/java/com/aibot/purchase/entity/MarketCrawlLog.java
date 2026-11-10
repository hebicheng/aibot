package com.aibot.purchase.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("market_crawl_logs")
public class MarketCrawlLog {
  @TableId(type = IdType.ASSIGN_UUID)
  private String id;
  private LocalDateTime runStart;
  private LocalDateTime runEnd;
  private String status;
  private String productCode;
  private LocalDate quoteDate;
  private Integer rowsFound;
  private Integer rowsUpserted;
  private Integer rowsSkipped;
  private String errorMsg;
}

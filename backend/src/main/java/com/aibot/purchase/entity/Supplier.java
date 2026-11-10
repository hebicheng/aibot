package com.aibot.purchase.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("suppliers")
public class Supplier {
  @TableId(type = IdType.ASSIGN_UUID)
  private String id;
  private String ownerId;
  private String name;
  private Integer rating;
  private String paymentTerms;
  private Integer deliveryDays;
  private String riskNote;
  private LocalDateTime createdAt;
}

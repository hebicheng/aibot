package com.aibot.purchase.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("users")
public class User {
  @TableId(type = IdType.ASSIGN_UUID)
  private String id;
  private String username;
  private String passwordHash;
  private String openId;
  private String unionId;
  private String nickname;
  private String avatarUrl;
  private String companyName;
  private String roleName;
  private LocalDateTime createdAt;
  private LocalDateTime updatedAt;
}

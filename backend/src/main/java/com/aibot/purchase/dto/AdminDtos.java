package com.aibot.purchase.dto;

import java.time.LocalDateTime;

/** 超级用户后台所需的 DTO。 */
public final class AdminDtos {
  private AdminDtos() {
  }

  /** 用户清单条目：概览用途，不含任何业务明细金额。 */
  public record AdminUserDto(
      String id,
      String username,
      String nickname,
      String companyName,
      String roleName,
      long projectCount,
      long batchCount,
      long documentCount,
      LocalDateTime createdAt,
      LocalDateTime lastActiveAt
  ) {
  }
}

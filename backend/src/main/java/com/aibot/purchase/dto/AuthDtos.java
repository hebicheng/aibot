package com.aibot.purchase.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;

public final class AuthDtos {
  private AuthDtos() {
  }

  public record WechatLoginRequest(
      @NotBlank String code,
      String nickname,
      String avatarUrl
  ) {
  }

  public record LoginRequest(
      @NotBlank String username,
      @NotBlank @Size(min = 6, max = 72) String password
  ) {
  }

  public record UserProfile(
      String id,
      String nickname,
      String avatarUrl,
      String companyName,
      String roleName,
      /** 真实登录账号是否为超级用户（切换身份后仍为 true，前端据此保留入口） */
      boolean superAdmin,
      /** 正在以他人身份浏览时的超管本人信息，否则为 null */
      ActingAs actingAs
  ) {
    public record ActingAs(String id, String nickname) {
    }
  }

  /**
   * 个人资料修改：字段传 null 表示「不修改」；昵称与单位名称至少传一个（Service 校验）。
   * 昵称只影响本人显示（右上角、聊天自称），不回溯改历史单据的项目经理。
   */
  public record UserProfileUpdateRequest(
      @Size(max = 20, message = "昵称最多 20 个字") String nickname,
      String companyName
  ) {
    public boolean isEmpty() {
      return (nickname == null || nickname.isBlank())
          && (companyName == null || companyName.isBlank());
    }
  }

  public record LoginResponse(
      String token,
      Instant expiresAt,
      UserProfile user
  ) {
  }
}

package com.aibot.purchase.auth;

/**
 * 当前请求的生效身份。
 *
 * <p>普通用户：id 就是本人，realUserId 为 null，superAdmin 为 false。
 *
 * <p>超级用户切换身份（X-Act-As）：id 是被查看的用户，realUserId / realNickname 是超管本人，
 * 这样所有既有业务代码仍然只用 {@code id} 做数据隔离，无需任何改动。
 */
public record AuthenticatedUser(
    String id,
    String openId,
    String nickname,
    String companyName,
    String roleName,
    String realUserId,
    String realNickname,
    boolean superAdmin
) {
  /** 超级用户的角色名（users.role_name）。 */
  public static final String SUPER_ADMIN_ROLE = "超管";

  /** 是否正在以他人身份浏览。 */
  public boolean actingAs() {
    return realUserId != null && !realUserId.equals(id);
  }

  public static boolean isSuperAdminRole(String roleName) {
    return roleName != null && SUPER_ADMIN_ROLE.equals(roleName.trim());
  }

  /** 切换为目标用户后的身份，保留超管本人信息。 */
  public AuthenticatedUser actAs(AuthenticatedUser target) {
    return new AuthenticatedUser(
        target.id(),
        target.openId(),
        target.nickname(),
        target.companyName(),
        target.roleName(),
        id,
        nickname,
        true
    );
  }
}

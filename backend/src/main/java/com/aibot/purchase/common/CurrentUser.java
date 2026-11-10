package com.aibot.purchase.common;

import com.aibot.purchase.auth.AuthenticatedUser;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

public final class CurrentUser {
  private CurrentUser() {
  }

  public static AuthenticatedUser get() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication == null || !(authentication.getPrincipal() instanceof AuthenticatedUser user)) {
      throw new ApiException(org.springframework.http.HttpStatus.UNAUTHORIZED, "未登录或登录已过期");
    }
    return user;
  }
}

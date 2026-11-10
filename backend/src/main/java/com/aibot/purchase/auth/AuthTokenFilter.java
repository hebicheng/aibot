package com.aibot.purchase.auth;

import com.aibot.purchase.common.ApiException;
import com.aibot.purchase.common.ApiResponse;
import com.aibot.purchase.service.UserService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class AuthTokenFilter extends OncePerRequestFilter {
  /** 超级用户切换查看身份：请求头携带目标用户 id，仅当真实登录账号是超管时生效。 */
  private static final String ACT_AS_HEADER = "X-Act-As";

  private final TokenService tokenService;
  private final UserService userService;
  private final ObjectMapper objectMapper;

  public AuthTokenFilter(TokenService tokenService, UserService userService, ObjectMapper objectMapper) {
    this.tokenService = tokenService;
    this.userService = userService;
    this.objectMapper = objectMapper;
  }

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
    try {
      if (authorization != null && authorization.startsWith("Bearer ")) {
        String userId = tokenService.verifyAndGetUserId(authorization.substring(7));
        AuthenticatedUser user = userService.getAuthenticatedUser(userId);
        user = switchIdentity(user, request.getHeader(ACT_AS_HEADER));
        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
            user,
            null,
            List.of(new SimpleGrantedAuthority("ROLE_USER"))
        );
        SecurityContextHolder.getContext().setAuthentication(authentication);
      }
      filterChain.doFilter(request, response);
    } catch (ApiException exception) {
      response.setStatus(exception.status().value());
      response.setCharacterEncoding("UTF-8");
      response.setContentType("application/json");
      objectMapper.writeValue(response.getWriter(), ApiResponse.error(exception.getMessage()));
    }
  }

  /**
   * 超管带头切换视角。目标用户不存在或非超管试图切换时，一律静默回落本人身份，
   * 不把切换失败升级成 401，避免误伤正常请求。
   */
  private AuthenticatedUser switchIdentity(AuthenticatedUser current, String actAsUserId) {
    if (!current.superAdmin() || actAsUserId == null || actAsUserId.isBlank()
        || actAsUserId.equals(current.id())) {
      return current;
    }
    try {
      return current.actAs(userService.getAuthenticatedUser(actAsUserId.trim()));
    } catch (ApiException ignored) {
      return current;
    }
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    String path = request.getRequestURI();
    return path.equals("/api/auth/login")
        || path.equals("/api/auth/wechat-login")
        || path.equals("/actuator/health");
  }
}

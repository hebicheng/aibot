package com.aibot.purchase.service;

import com.aibot.purchase.auth.TokenService;
import com.aibot.purchase.dto.AuthDtos.LoginResponse;
import com.aibot.purchase.dto.AuthDtos.LoginRequest;
import com.aibot.purchase.dto.AuthDtos.UserProfile;
import com.aibot.purchase.dto.AuthDtos.WechatLoginRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {
  private final WechatClient wechatClient;
  private final UserService userService;
  private final TokenService tokenService;

  public AuthService(WechatClient wechatClient, UserService userService, TokenService tokenService) {
    this.wechatClient = wechatClient;
    this.userService = userService;
    this.tokenService = tokenService;
  }

  @Transactional
  public LoginResponse wechatLogin(WechatLoginRequest request) {
    WechatClient.WechatSession session = wechatClient.exchangeCode(request.code());
    UserProfile user = userService.upsertWechatUser(
        session.openId(),
        session.unionId(),
        request.nickname(),
        request.avatarUrl()
    );
    TokenService.IssuedToken token = tokenService.issue(user.id());
    return new LoginResponse(token.token(), token.expiresAt(), user);
  }

  public LoginResponse login(LoginRequest request) {
    UserProfile user = userService.authenticate(request.username(), request.password());
    TokenService.IssuedToken token = tokenService.issue(user.id());
    return new LoginResponse(token.token(), token.expiresAt(), user);
  }
}

package com.aibot.purchase.controller;

import com.aibot.purchase.auth.WechatProperties;
import com.aibot.purchase.common.ApiResponse;
import com.aibot.purchase.dto.AuthDtos.LoginResponse;
import com.aibot.purchase.dto.AuthDtos.LoginRequest;
import com.aibot.purchase.dto.AuthDtos.WechatLoginRequest;
import com.aibot.purchase.service.AuthService;
import com.aibot.purchase.service.QrLoginService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
  private final AuthService authService;
  private final QrLoginService qrLoginService;
  private final WechatProperties wechatProperties;

  public AuthController(
      AuthService authService,
      QrLoginService qrLoginService,
      WechatProperties wechatProperties
  ) {
    this.authService = authService;
    this.qrLoginService = qrLoginService;
    this.wechatProperties = wechatProperties;
  }

  /**
   * 公开登录配置：把小程序码版本透给前端，前端据此决定是否显示账号密码登录。
   * develop（本地联调）与 trial（体验版，小程序未认证时小程序码不可用）都开放账号密码登录；
   * release 只给微信扫码（2026-11-06）。
   */
  @GetMapping("/config")
  public ApiResponse<AuthConfig> config() {
    String envVersion = wechatProperties.qrEnvVersion();
    return ApiResponse.ok(new AuthConfig(
        envVersion == null || envVersion.isBlank() ? "release" : envVersion
    ));
  }

  @PostMapping("/login")
  public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
    return ApiResponse.ok(authService.login(request));
  }

  @PostMapping("/wechat-login")
  public ApiResponse<LoginResponse> wechatLogin(@Valid @RequestBody WechatLoginRequest request) {
    return ApiResponse.ok(authService.wechatLogin(request));
  }

  /** 小程序码扫码登录：创建扫码会话，返回 state + 小程序码图片（data URL）。 */
  @GetMapping("/wechat-qr")
  public ApiResponse<QrLoginService.QrCreateResult> createQrSession() {
    return ApiResponse.ok(qrLoginService.createQrSession());
  }

  /** 小程序码扫码登录：小程序确认页确认后回调（body: state + wx.login 的 code）。 */
  @PostMapping("/wechat-qr/confirm")
  public ApiResponse<LoginResponse> confirm(@RequestBody QrConfirmRequest request) {
    return ApiResponse.ok(qrLoginService.confirmByMiniProgram(request.state(), request.code()));
  }

  /** 小程序码扫码登录：前端轮询扫码状态（pending / success / expired）。 */
  @GetMapping("/wechat-qr/status")
  public ApiResponse<QrLoginService.QrStatus> qrStatus(@RequestParam String state) {
    return ApiResponse.ok(qrLoginService.getStatus(state));
  }

  @PostMapping("/logout")
  public ApiResponse<Void> logout() {
    return ApiResponse.ok();
  }

  public record QrConfirmRequest(String state, String code) {
  }

  public record AuthConfig(String qrEnvVersion) {
  }
}

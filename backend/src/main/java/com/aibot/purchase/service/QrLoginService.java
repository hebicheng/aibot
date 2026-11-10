package com.aibot.purchase.service;

import com.aibot.purchase.auth.TokenService;
import com.aibot.purchase.auth.WechatProperties;
import com.aibot.purchase.common.ApiException;
import com.aibot.purchase.common.QrCodeUtil;
import com.aibot.purchase.dto.AuthDtos.LoginResponse;
import com.aibot.purchase.dto.AuthDtos.UserProfile;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * 小程序码扫码登录会话管理：
 * 1. Web 端请求 createQrSession 拿 state + 小程序码（PNG base64）+ 普通二维码，渲染二维码；
 * 2. 用户微信扫小程序码 → 打开小程序登录确认页（page 携带 scene=state）；
 *    或用户在小程序内「扫一扫」扫普通二维码 → 自动完成网页登录；
 * 3. 用户在小程序里点确认（或扫一扫自动）→ wx.login() 拿 code → 调 confirmByMiniProgram(state, code)；
 * 4. 后端 exchangeCode 换 openid/unionid、签发 token 并标记会话完成；
 * 5. Web 端轮询 getStatus(state) 直到 success（拿到 token）或 expired。
 *
 * 会话保存在内存（单机部署足够），TTL 默认 5 分钟，查询时惰性清理。
 */
@Service
public class QrLoginService {
  private static final Logger log = LoggerFactory.getLogger(QrLoginService.class);
  private static final long TTL_SECONDS = 300;

  private final WechatClient wechatClient;
  private final UserService userService;
  private final TokenService tokenService;
  private final WechatProperties wechatProperties;
  private final Map<String, QrSession> sessions = new ConcurrentHashMap<>();

  public QrLoginService(
      WechatClient wechatClient,
      UserService userService,
      TokenService tokenService,
      WechatProperties wechatProperties
  ) {
    this.wechatClient = wechatClient;
    this.userService = userService;
    this.tokenService = tokenService;
    this.wechatProperties = wechatProperties;
  }

  /** 创建扫码会话，返回 state 与小程序码（data URL；mock 模式返回空字符串）。 */
  public QrCreateResult createQrSession() {
    sweepExpired();
    String state = UUID.randomUUID().toString().replace("-", "");
    sessions.put(state, new QrSession(state, Instant.now().plusSeconds(TTL_SECONDS)));
    String page = wechatProperties.qrPage() == null || wechatProperties.qrPage().isBlank()
        ? "pages/login/confirm"
        : wechatProperties.qrPage();
    String envVersion = wechatProperties.qrEnvVersion() == null || wechatProperties.qrEnvVersion().isBlank()
        ? "release"
        : wechatProperties.qrEnvVersion();
    // 普通二维码：内容为 state 本身（32 位 hex），供小程序内 wx.scanCode 识别完成网页登录。
    // 小程序码只能由微信相机扫，wx.scanCode 无法识别，故需额外渲染一张普通二维码。
    String plainQr = "";
    try {
      byte[] plain = QrCodeUtil.generatePng(state, 320);
      plainQr = "data:image/png;base64," + Base64.getEncoder().encodeToString(plain);
    } catch (Exception ex) {
      // 普通二维码生成失败不阻断主流程（小程序码路径仍可用），仅记录
      log.warn("普通二维码生成失败：{}", ex.getMessage());
    }
    try {
      byte[] png = wechatClient.generateMiniProgramQrCode(state, page, envVersion);
      if (png.length == 0) {
        return new QrCreateResult(state, "", plainQr, page, envVersion);
      }
      String dataUrl = "data:image/png;base64," + Base64.getEncoder().encodeToString(png);
      return new QrCreateResult(state, dataUrl, plainQr, page, envVersion);
    } catch (ApiException ex) {
      // 小程序码生成失败（页面未发布/未上传等）：返回空图，前端显示错误提示 + 刷新
      return new QrCreateResult(state, "", plainQr, page, envVersion, ex.getMessage());
    }
  }

  /** 小程序确认登录：用户在小程序确认页点确认后携带 wx.login 的 code 回调。 */
  public LoginResponse confirmByMiniProgram(String state, String code) {
    QrSession session = sessions.get(state);
    if (session == null || session.expiresAt.isBefore(Instant.now())) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "扫码会话不存在或已过期，请刷新二维码");
    }
    if (session.loginResponse != null) {
      return session.loginResponse;
    }
    WechatClient.WechatSession wechat = wechatClient.exchangeCode(code);
    UserProfile user = userService.upsertWechatUser(
        wechat.openId(),
        wechat.unionId(),
        "采购用户",
        null
    );
    TokenService.IssuedToken token = tokenService.issue(user.id());
    LoginResponse loginResponse = new LoginResponse(token.token(), token.expiresAt(), user);
    session.loginResponse = loginResponse;
    sessions.put(state, session);
    return loginResponse;
  }

  /** 轮询查询：pending / success / expired。 */
  public QrStatus getStatus(String state) {
    QrSession session = sessions.get(state);
    if (session == null) {
      return new QrStatus("expired", null, null, null);
    }
    if (session.loginResponse != null) {
      return new QrStatus("success", session.loginResponse.token(), session.loginResponse.user(), null);
    }
    if (session.expiresAt.isBefore(Instant.now())) {
      sessions.remove(state);
      return new QrStatus("expired", null, null, null);
    }
    return new QrStatus("pending", null, null, null);
  }

  private void sweepExpired() {
    Instant now = Instant.now();
    sessions.entrySet().removeIf(entry -> entry.getValue().expiresAt.isBefore(now));
  }

  public record QrCreateResult(
      String state,
      String qrImageDataUrl,
      String plainQrImageDataUrl,
      String page,
      String envVersion,
      String error
  ) {
    public QrCreateResult(String state, String qrImageDataUrl, String plainQrImageDataUrl, String page, String envVersion) {
      this(state, qrImageDataUrl, plainQrImageDataUrl, page, envVersion, null);
    }
  }

  public record QrStatus(String status, String token, UserProfile user, String error) {
  }

  private static final class QrSession {
    final String state;
    final Instant expiresAt;
    volatile LoginResponse loginResponse;

    QrSession(String state, Instant expiresAt) {
      this.state = state;
      this.expiresAt = expiresAt;
    }
  }
}

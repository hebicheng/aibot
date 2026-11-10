package com.aibot.purchase.service;

import com.aibot.purchase.auth.WechatProperties;
import com.aibot.purchase.common.ApiException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class WechatClient {
  private final WechatProperties properties;
  private final RestClient restClient;
  private final ObjectMapper objectMapper;

  // access_token 缓存：有效期 7200s，提前 300s 过期换取
  private volatile String accessTokenCache;
  private volatile Instant accessTokenExpiresAt = Instant.EPOCH;

  public WechatClient(WechatProperties properties, RestClient.Builder builder, ObjectMapper objectMapper) {
    this.properties = properties;
    this.restClient = builder.baseUrl("https://api.weixin.qq.com").build();
    this.objectMapper = objectMapper;
  }

  /**
   * 微信接口统一把响应 Content-Type 标成 {@code text/plain}（非 application/json），
   * Spring 默认没有能把 text/plain 反序列化成 Map 的 HttpMessageConverter，会抛 500。
   * 因此先按 String 取回，再用 Jackson 手动解析。
   */
  private Map<?, ?> getJsonAsMap(RestClient.RequestHeadersSpec<?> spec) {
    String body;
    try {
      body = spec.retrieve().body(String.class);
    } catch (RestClientException ex) {
      throw new ApiException(HttpStatus.BAD_GATEWAY, "调用微信接口失败：" + ex.getMessage());
    }
    if (body == null || body.isBlank()) {
      throw new ApiException(HttpStatus.BAD_GATEWAY, "微信接口返回空响应");
    }
    try {
      return objectMapper.readValue(body, new TypeReference<Map<String, Object>>() {});
    } catch (Exception ex) {
      throw new ApiException(HttpStatus.BAD_GATEWAY, "解析微信响应失败：" + ex.getMessage() + "；原始响应：" + body);
    }
  }

  /** 小程序登录：code 换 openid/unionid（wx.login -> jscode2session） */
  public WechatSession exchangeCode(String code) {
    if (properties.mockLogin()) {
      return new WechatSession("mock-openid", "mock-union-id");
    }
    if (!StringUtils.hasText(properties.appId()) || !StringUtils.hasText(properties.appSecret())) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "未配置微信小程序 appId/appSecret");
    }
    Map<?, ?> response = getJsonAsMap(restClient.get()
        .uri(uriBuilder -> uriBuilder
            .path("/sns/jscode2session")
            .queryParam("appid", properties.appId())
            .queryParam("secret", properties.appSecret())
            .queryParam("js_code", code)
            .queryParam("grant_type", "authorization_code")
            .build()));
    if (response == null || response.get("openid") == null) {
      throw new ApiException(HttpStatus.UNAUTHORIZED, "微信登录失败：" + (response == null ? "empty response" : response));
    }
    return new WechatSession(String.valueOf(response.get("openid")),
        response.get("unionid") == null ? null : String.valueOf(response.get("unionid")));
  }

  /** 小程序 access_token（cgi-bin/token），带缓存。 */
  public String miniProgramAccessToken() {
    if (properties.mockLogin()) {
      return "mock-access-token";
    }
    if (!StringUtils.hasText(properties.appId()) || !StringUtils.hasText(properties.appSecret())) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "未配置微信小程序 appId/appSecret");
    }
    if (accessTokenCache != null && Instant.now().isBefore(accessTokenExpiresAt)) {
      return accessTokenCache;
    }
    Map<?, ?> response = getJsonAsMap(restClient.get()
        .uri(uriBuilder -> uriBuilder
            .path("/cgi-bin/token")
            .queryParam("grant_type", "client_credential")
            .queryParam("appid", properties.appId())
            .queryParam("secret", properties.appSecret())
            .build()));
    if (response == null || response.get("access_token") == null) {
      throw new ApiException(HttpStatus.UNAUTHORIZED, "获取小程序 access_token 失败：" + (response == null ? "empty" : response));
    }
    accessTokenCache = String.valueOf(response.get("access_token"));
    long expiresIn = response.get("expires_in") == null ? 7200 : Long.parseLong(String.valueOf(response.get("expires_in")));
    accessTokenExpiresAt = Instant.now().plusSeconds(Math.max(expiresIn - 300, 60));
    return accessTokenCache;
  }

  /**
   * 生成小程序码（wxacode.getUnlimited）：Web 扫码后打开小程序指定页面（登录确认页）。
   * 返回 PNG 二进制，由调用方转 base64 data URL 返回前端。
   *
   * 注意：微信出错时返回的是 JSON 文本（{"errcode":...,"errmsg":...}）而非 PNG，
   * 不能无脑当图片返回，否则前端 <img> 静默失败、看不到任何报错。这里先校验响应是否为错误 JSON。
   */
  public byte[] generateMiniProgramQrCode(String scene, String page, String envVersion) {
    if (properties.mockLogin()) {
      // mock：不真实生成，返回占位字节（前端在 mock 模式用占位图 + 模拟确认按钮）
      return new byte[0];
    }
    String token = miniProgramAccessToken();
    byte[] image = postWxacode(token, scene, page, envVersion);
    // 40001 = access_token 失效/被其它端抢占，清缓存重新换取后再试一次（自愈多端抢 token）
    if (isWechatError(image, 40001)) {
      log.warn("生成小程序码时 access_token 失效，清缓存重试一次");
      accessTokenCache = null;
      accessTokenExpiresAt = Instant.EPOCH;
      token = miniProgramAccessToken();
      image = postWxacode(token, scene, page, envVersion);
    }
    if (image == null || image.length == 0) {
      throw new ApiException(HttpStatus.BAD_GATEWAY, "生成小程序码失败：微信返回空");
    }
    if (isWechatError(image)) {
      throw new ApiException(HttpStatus.BAD_GATEWAY, "生成小程序码失败：" + wechatErrorMessage(image));
    }
    return image;
  }

  private byte[] postWxacode(String token, String scene, String page, String envVersion) {
    String bodyJson = """
        {"scene":"%s","page":"%s","check_path":false,"env_version":"%s","width":280}
        """.formatted(scene, page, envVersion);
    return restClient.post()
        .uri(uriBuilder -> uriBuilder
            .path("/wxa/getwxacodeunlimit")
            .queryParam("access_token", token)
            .build())
        .contentType(MediaType.APPLICATION_JSON)
        .body(bodyJson)
        .retrieve()
        .body(byte[].class);
  }

  /** 微信错误响应是 JSON 文本（以 { 开头），且含 errcode。判断字节是否为错误响应。 */
  private boolean isWechatError(byte[] data) {
    return isWechatError(data, -1);
  }

  private boolean isWechatError(byte[] data, int errcode) {
    if (data == null || data.length < 2 || data[0] != '{') {
      return false;
    }
    try {
      Map<?, ?> map = objectMapper.readValue(data, new TypeReference<Map<String, Object>>() {});
      Object code = map.get("errcode");
      if (code == null) {
        return false;
      }
      int c = Integer.parseInt(String.valueOf(code));
      return errcode < 0 || c == errcode;
    } catch (Exception ex) {
      return false;
    }
  }

  private String wechatErrorMessage(byte[] data) {
    try {
      Map<?, ?> map = objectMapper.readValue(data, new TypeReference<Map<String, Object>>() {});
      return String.valueOf(map.get("errmsg"));
    } catch (Exception ex) {
      return "微信返回错误响应";
    }
  }

  private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(WechatClient.class);

  public record WechatSession(String openId, String unionId) {
  }
}

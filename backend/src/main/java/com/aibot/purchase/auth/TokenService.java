package com.aibot.purchase.auth;

import com.aibot.purchase.common.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class TokenService {
  private final SecurityProperties properties;

  public TokenService(SecurityProperties properties) {
    this.properties = properties;
  }

  public IssuedToken issue(String userId) {
    long expiresAt = Instant.now().plusSeconds(properties.tokenTtlSeconds()).getEpochSecond();
    String subject = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(userId.getBytes(StandardCharsets.UTF_8));
    String body = subject + "." + expiresAt + "." + UUID.randomUUID();
    String signature = sign(body);
    return new IssuedToken(body + "." + signature, Instant.ofEpochSecond(expiresAt));
  }

  public String verifyAndGetUserId(String token) {
    String[] parts = token == null ? new String[0] : token.split("\\.");
    if (parts.length != 4) {
      throw new ApiException(HttpStatus.UNAUTHORIZED, "无效登录凭证");
    }
    String body = parts[0] + "." + parts[1] + "." + parts[2];
    if (!MessageDigest.isEqual(sign(body).getBytes(StandardCharsets.UTF_8), parts[3].getBytes(StandardCharsets.UTF_8))) {
      throw new ApiException(HttpStatus.UNAUTHORIZED, "登录凭证校验失败");
    }
    long expiresAt = Long.parseLong(parts[1]);
    if (Instant.now().getEpochSecond() > expiresAt) {
      throw new ApiException(HttpStatus.UNAUTHORIZED, "登录已过期");
    }
    return new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8);
  }

  private String sign(String body) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(properties.tokenSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception ex) {
      throw new IllegalStateException("Failed to sign token", ex);
    }
  }

  public record IssuedToken(String token, Instant expiresAt) {
  }
}

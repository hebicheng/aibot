package com.aibot.purchase.controller;

import com.aibot.purchase.common.ApiException;
import com.aibot.purchase.common.ApiResponse;
import com.aibot.purchase.common.CurrentUser;
import com.aibot.purchase.dto.AuthDtos.UserProfile;
import com.aibot.purchase.dto.AuthDtos.UserProfileUpdateRequest;
import com.aibot.purchase.service.UserService;
import com.aibot.purchase.service.UserSettingsService;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/me")
public class UserController {
  private final UserService userService;
  private final UserSettingsService userSettingsService;

  public UserController(UserService userService, UserSettingsService userSettingsService) {
    this.userService = userService;
    this.userSettingsService = userSettingsService;
  }

  @GetMapping
  public ApiResponse<UserProfile> me() {
    return ApiResponse.ok(userService.profileOf(CurrentUser.get()));
  }

  /** 修改个人资料：昵称 / 单位名称，传哪个改哪个（都不传报错）。 */
  @PutMapping
  public ApiResponse<UserProfile> update(@Valid @RequestBody UserProfileUpdateRequest request) {
    if (request.isEmpty()) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "请至少填写昵称或单位名称");
    }
    userService.updateProfile(CurrentUser.get().id(), request.nickname(), request.companyName());
    // 回读当前视角档案，保留超管的 superAdmin / actingAs 信息，避免前端身份横幅丢失
    return ApiResponse.ok(userService.profileOf(CurrentUser.get()));
  }

  /** 用户偏好设置（user_settings JSON）：GET 返回整份，PUT 深合并 patch */
  @GetMapping("/settings")
  public ApiResponse<Map<String, Object>> settings() {
    return ApiResponse.ok(userSettingsService.get(CurrentUser.get().id()));
  }

  @PutMapping("/settings")
  public ApiResponse<Map<String, Object>> updateSettings(
      @RequestBody Map<String, Object> patch
  ) {
    return ApiResponse.ok(userSettingsService.update(CurrentUser.get().id(), patch));
  }
}

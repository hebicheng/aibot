package com.aibot.purchase.controller;

import com.aibot.purchase.auth.AuthenticatedUser;
import com.aibot.purchase.common.ApiException;
import com.aibot.purchase.common.ApiResponse;
import com.aibot.purchase.common.CurrentUser;
import com.aibot.purchase.dto.AdminDtos.AdminUserDto;
import com.aibot.purchase.service.AdminService;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 超级用户专用接口（users.role_name = '超管'）。
 *
 * <p>这里只提供跨用户的清单，供前端选择「以谁的身份查看」；业务数据本身仍然走原有接口，
 * 由请求头 X-Act-As 切换生效身份。
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {
  private final AdminService adminService;

  public AdminController(AdminService adminService) {
    this.adminService = adminService;
  }

  @GetMapping("/users")
  public ApiResponse<List<AdminUserDto>> users(@RequestParam(required = false) String query) {
    AuthenticatedUser current = CurrentUser.get();
    if (!current.superAdmin()) {
      throw new ApiException(HttpStatus.FORBIDDEN, "仅超级用户可以查看用户清单");
    }
    return ApiResponse.ok(adminService.listUsers(query));
  }
}

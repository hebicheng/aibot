package com.aibot.purchase.controller;

import com.aibot.purchase.common.ApiResponse;
import com.aibot.purchase.common.CurrentUser;
import com.aibot.purchase.dto.BusinessDtos.DashboardOverviewDto;
import com.aibot.purchase.service.BusinessQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {
  private final BusinessQueryService businessQueryService;

  public DashboardController(BusinessQueryService businessQueryService) {
    this.businessQueryService = businessQueryService;
  }

  @GetMapping
  public ApiResponse<DashboardOverviewDto> overview() {
    return ApiResponse.ok(businessQueryService.dashboard(CurrentUser.get().id()));
  }
}

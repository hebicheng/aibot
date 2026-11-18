package com.aibot.purchase.controller;

import com.aibot.purchase.common.ApiResponse;
import com.aibot.purchase.common.CurrentUser;
import com.aibot.purchase.service.CompanyTransactionService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 公司往来：本公司与供货商的采购 / 进项发票往来汇总（2026-11-17）。 */
@RestController
@RequestMapping("/api/company-transactions")
public class CompanyTransactionController {
  private final CompanyTransactionService service;

  public CompanyTransactionController(CompanyTransactionService service) {
    this.service = service;
  }

  /** projectId 为空 = 全部项目。 */
  @GetMapping
  public ApiResponse<CompanyTransactionService.Overview> overview(
      @RequestParam(required = false) String projectId) {
    return ApiResponse.ok(service.overview(CurrentUser.get().id(), projectId));
  }
}

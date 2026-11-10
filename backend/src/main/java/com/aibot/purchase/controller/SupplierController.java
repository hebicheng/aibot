package com.aibot.purchase.controller;

import com.aibot.purchase.common.ApiResponse;
import com.aibot.purchase.common.CurrentUser;
import com.aibot.purchase.dto.BusinessDtos.SupplierDto;
import com.aibot.purchase.service.BusinessQueryService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/suppliers")
public class SupplierController {
  private final BusinessQueryService businessQueryService;

  public SupplierController(BusinessQueryService businessQueryService) {
    this.businessQueryService = businessQueryService;
  }

  @GetMapping
  public ApiResponse<List<SupplierDto>> list() {
    return ApiResponse.ok(businessQueryService.suppliers(CurrentUser.get().id()));
  }
}

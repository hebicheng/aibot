package com.aibot.purchase.controller;

import com.aibot.purchase.common.ApiResponse;
import com.aibot.purchase.common.CurrentUser;
import com.aibot.purchase.dto.BusinessDtos.BatchCreateRequest;
import com.aibot.purchase.dto.BusinessDtos.BatchDetailDto;
import com.aibot.purchase.dto.BusinessDtos.BatchDto;
import com.aibot.purchase.dto.BusinessDtos.BatchUpdateRequest;
import com.aibot.purchase.dto.BusinessDtos.FinanceRecordDto;
import com.aibot.purchase.dto.BusinessDtos.FinanceSaveRequest;
import com.aibot.purchase.dto.BusinessDtos.MaterialDto;
import com.aibot.purchase.dto.BusinessDtos.MaterialSaveRequest;
import com.aibot.purchase.service.BusinessQueryService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/batches")
public class BatchController {
  private final BusinessQueryService businessQueryService;

  public BatchController(BusinessQueryService businessQueryService) {
    this.businessQueryService = businessQueryService;
  }

  @GetMapping
  public ApiResponse<List<BatchDto>> list(@RequestParam(required = false) String projectId) {
    return ApiResponse.ok(businessQueryService.listBatches(CurrentUser.get().id(), projectId));
  }

  @PostMapping
  public ApiResponse<BatchDto> create(@Valid @RequestBody BatchCreateRequest request) {
    return ApiResponse.ok(businessQueryService.createBatch(CurrentUser.get().id(), request));
  }

  @GetMapping("/{id}")
  public ApiResponse<BatchDetailDto> detail(@PathVariable String id) {
    return ApiResponse.ok(businessQueryService.batchDetail(CurrentUser.get().id(), id));
  }

  @PutMapping("/{id}")
  public ApiResponse<BatchDto> update(@PathVariable String id, @RequestBody BatchUpdateRequest request) {
    return ApiResponse.ok(businessQueryService.updateBatch(CurrentUser.get().id(), id, request));
  }

  @DeleteMapping("/{id}")
  public ApiResponse<Void> delete(@PathVariable String id) {
    businessQueryService.deleteBatch(CurrentUser.get().id(), id);
    return ApiResponse.ok();
  }

  @PostMapping("/{id}/materials")
  public ApiResponse<MaterialDto> saveMaterial(@PathVariable String id, @Valid @RequestBody MaterialSaveRequest request) {
    return ApiResponse.ok(businessQueryService.saveMaterial(CurrentUser.get().id(), id, request));
  }

  @DeleteMapping("/{id}/materials/{materialId}")
  public ApiResponse<Void> deleteMaterial(@PathVariable String id, @PathVariable String materialId) {
    businessQueryService.deleteMaterial(CurrentUser.get().id(), id, materialId);
    return ApiResponse.ok();
  }

  @PostMapping("/{id}/finance-records")
  public ApiResponse<FinanceRecordDto> saveFinance(@PathVariable String id, @Valid @RequestBody FinanceSaveRequest request) {
    return ApiResponse.ok(businessQueryService.saveFinance(CurrentUser.get().id(), id, request));
  }
}

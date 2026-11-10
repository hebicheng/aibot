package com.aibot.purchase.controller;

import com.aibot.purchase.common.ApiResponse;
import com.aibot.purchase.common.CurrentUser;
import com.aibot.purchase.dto.BusinessDtos.ProjectCreateRequest;
import com.aibot.purchase.dto.BusinessDtos.ProjectDto;
import com.aibot.purchase.dto.BusinessDtos.ProjectUpdateRequest;
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
@RequestMapping("/api/projects")
public class ProjectController {
  private final BusinessQueryService businessQueryService;

  public ProjectController(BusinessQueryService businessQueryService) {
    this.businessQueryService = businessQueryService;
  }

  @GetMapping
  public ApiResponse<List<ProjectDto>> list(@RequestParam(required = false) String query) {
    return ApiResponse.ok(businessQueryService.listProjects(CurrentUser.get().id(), query));
  }

  @PostMapping
  public ApiResponse<ProjectDto> create(@Valid @RequestBody ProjectCreateRequest request) {
    return ApiResponse.ok(businessQueryService.createProject(CurrentUser.get().id(), request));
  }

  @PutMapping("/{id}")
  public ApiResponse<ProjectDto> update(
      @PathVariable String id,
      @RequestBody ProjectUpdateRequest request
  ) {
    return ApiResponse.ok(businessQueryService.updateProject(CurrentUser.get().id(), id, request));
  }

  @DeleteMapping("/{id}")
  public ApiResponse<Void> delete(@PathVariable String id) {
    businessQueryService.deleteProject(CurrentUser.get().id(), id);
    return ApiResponse.ok();
  }
}

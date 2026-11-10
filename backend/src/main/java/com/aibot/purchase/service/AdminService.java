package com.aibot.purchase.service;

import com.aibot.purchase.dto.AdminDtos.AdminUserDto;
import com.aibot.purchase.entity.Batch;
import com.aibot.purchase.entity.Document;
import com.aibot.purchase.entity.Project;
import com.aibot.purchase.entity.User;
import com.aibot.purchase.mapper.BatchMapper;
import com.aibot.purchase.mapper.DocumentMapper;
import com.aibot.purchase.mapper.ProjectMapper;
import com.aibot.purchase.mapper.UserMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 超级用户后台查询：只做跨用户清单，不参与任何业务数据读写。
 *
 * <p>统计走三次全表取列 + 内存聚合，而不是 group by：用户量级很小，
 * 这样避免依赖列别名的返回大小写，也不会因逻辑删除条件漏加而算进已删数据。
 */
@Service
public class AdminService {
  private final UserMapper userMapper;
  private final ProjectMapper projectMapper;
  private final BatchMapper batchMapper;
  private final DocumentMapper documentMapper;

  public AdminService(
      UserMapper userMapper,
      ProjectMapper projectMapper,
      BatchMapper batchMapper,
      DocumentMapper documentMapper
  ) {
    this.userMapper = userMapper;
    this.projectMapper = projectMapper;
    this.batchMapper = batchMapper;
    this.documentMapper = documentMapper;
  }

  public List<AdminUserDto> listUsers(String keyword) {
    String text = keyword == null ? "" : keyword.trim();
    List<User> users = userMapper.selectList(Wrappers.<User>lambdaQuery()
        .and(StringUtils.hasText(text), wrapper -> wrapper
            .like(User::getNickname, text)
            .or()
            .like(User::getUsername, text)
            .or()
            .like(User::getCompanyName, text))
        .orderByDesc(User::getCreatedAt));

    List<Project> projects = projectMapper.selectList(null);
    List<Batch> batches = batchMapper.selectList(
        Wrappers.<Batch>query().select("project_id", "id"));
    List<Document> documents = documentMapper.selectList(
        Wrappers.<Document>query().select("user_id", "created_at"));

    Map<String, String> projectOwner = new HashMap<>();
    Map<String, Long> projectCount = new HashMap<>();
    Map<String, LocalDateTime> lastActive = new HashMap<>();
    for (Project project : projects) {
      if (project.getOwnerId() == null) {
        continue;
      }
      projectOwner.put(project.getId(), project.getOwnerId());
      projectCount.merge(project.getOwnerId(), 1L, Long::sum);
      lastActive.merge(project.getOwnerId(), latest(project.getUpdatedAt(), project.getCreatedAt()),
          AdminService::later);
    }

    Map<String, Long> batchCount = new HashMap<>();
    for (Batch batch : batches) {
      String owner = projectOwner.get(batch.getProjectId());
      if (owner != null) {
        batchCount.merge(owner, 1L, Long::sum);
      }
    }

    Map<String, Long> documentCount = new HashMap<>();
    for (Document document : documents) {
      if (document.getUserId() == null) {
        continue;
      }
      documentCount.merge(document.getUserId(), 1L, Long::sum);
      lastActive.merge(document.getUserId(), document.getCreatedAt(), AdminService::later);
    }

    return users.stream()
        .map(user -> new AdminUserDto(
            user.getId(),
            user.getUsername(),
            user.getNickname(),
            user.getCompanyName(),
            user.getRoleName(),
            projectCount.getOrDefault(user.getId(), 0L),
            batchCount.getOrDefault(user.getId(), 0L),
            documentCount.getOrDefault(user.getId(), 0L),
            user.getCreatedAt(),
            lastActive.get(user.getId()) == null ? user.getCreatedAt() : lastActive.get(user.getId())
        ))
        .sorted(Comparator.comparing(AdminUserDto::documentCount).reversed()
            .thenComparing(AdminUserDto::projectCount, Comparator.reverseOrder()))
        .collect(Collectors.toList());
  }

  private static LocalDateTime latest(LocalDateTime first, LocalDateTime second) {
    if (first == null) {
      return second;
    }
    if (second == null) {
      return first;
    }
    return first.isAfter(second) ? first : second;
  }

  private static LocalDateTime later(LocalDateTime current, LocalDateTime candidate) {
    if (candidate == null) {
      return current;
    }
    if (current == null) {
      return candidate;
    }
    return candidate.isAfter(current) ? candidate : current;
  }
}

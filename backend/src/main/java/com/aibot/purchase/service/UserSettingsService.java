package com.aibot.purchase.service;

import com.aibot.purchase.entity.UserSettings;
import com.aibot.purchase.mapper.UserSettingsMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 用户偏好设置（user_settings 单行 JSON）。
 *
 * <p>键规范：顶层为配置域，域内为键，如 {@code reconciliation.weekday}。
 * 新增配置零迁移；读取缺失域时返回默认值。
 *
 * <p>本期键（对账单每周提醒）：
 * <ul>
 *   <li>{@code reconciliation.enabled}：boolean，缺省 true</li>
 *   <li>{@code reconciliation.weekday}：1-7（周一~周日），缺省 1</li>
 *   <li>{@code reconciliation.hour}：0-23，缺省 9</li>
 * </ul>
 */
@Service
public class UserSettingsService {

  public record ReconciliationReminderSettings(boolean enabled, int weekday, int hour) {}

  private final UserSettingsMapper mapper;
  private final ObjectMapper objectMapper;

  public UserSettingsService(UserSettingsMapper mapper, ObjectMapper objectMapper) {
    this.mapper = mapper;
    this.objectMapper = objectMapper;
  }

  public Map<String, Object> get(String userId) {
    UserSettings row = mapper.selectById(userId);
    if (row == null || !StringUtils.hasText(row.getSettings())) {
      return new LinkedHashMap<>();
    }
    try {
      return objectMapper.readValue(row.getSettings(),
          new TypeReference<LinkedHashMap<String, Object>>() {});
    } catch (Exception ex) {
      return new LinkedHashMap<>();
    }
  }

  /** 深合并写入（只覆盖 patch 里的键），新增配置零迁移 */
  public Map<String, Object> update(String userId, Map<String, Object> patch) {
    Map<String, Object> current = get(userId);
    merge(current, patch);
    UserSettings row = mapper.selectById(userId);
    LocalDateTime now = LocalDateTime.now();
    if (row == null) {
      row = new UserSettings();
      row.setUserId(userId);
      row.setCreatedAt(now);
    }
    row.setSettings(writeJson(current));
    row.setUpdatedAt(now);
    if (row.getCreatedAt() == null) {
      row.setCreatedAt(now);
    }
    if (mapper.selectById(userId) == null) {
      mapper.insert(row);
    } else {
      mapper.updateById(row);
    }
    return current;
  }

  @SuppressWarnings("unchecked")
  private void merge(Map<String, Object> target, Map<String, Object> patch) {
    for (Map.Entry<String, Object> entry : patch.entrySet()) {
      String key = entry.getKey();
      Object value = entry.getValue();
      if (value instanceof Map<?, ?> nested && target.get(key) instanceof Map<?, ?>) {
        merge((Map<String, Object>) target.get(key), (Map<String, Object>) nested);
      } else if (value instanceof Map<?, ?> nested) {
        Map<String, Object> copy = new LinkedHashMap<>();
        nested.forEach((k, v) -> copy.put(String.valueOf(k), v));
        target.put(key, copy);
      } else {
        target.put(key, value);
      }
    }
  }

  private String writeJson(Map<String, Object> settings) {
    try {
      return objectMapper.writeValueAsString(settings);
    } catch (Exception ex) {
      return "{}";
    }
  }

  /** 周提醒配置（含缺省值）；weekday 1-7 = 周一~周日 */
  public ReconciliationReminderSettings reconciliationReminder(String userId) {
    Map<String, Object> domain = domain(userId, "reconciliation");
    boolean enabled = Boolean.TRUE.equals(domain.get("enabled"));
    int weekday = intValue(domain.get("weekday"), 1);
    int hour = intValue(domain.get("hour"), 9);
    weekday = Math.min(7, Math.max(1, weekday));
    hour = Math.min(23, Math.max(0, hour));
    return new ReconciliationReminderSettings(enabled, weekday, hour);
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> domain(String userId, String name) {
    Object raw = get(userId).get(name);
    return raw instanceof Map<?, ?> ? (Map<String, Object>) raw : new LinkedHashMap<>();
  }

  private static int intValue(Object value, int fallback) {
    if (value instanceof Number number) {
      return number.intValue();
    }
    try {
      return Integer.parseInt(String.valueOf(value));
    } catch (Exception ex) {
      return fallback;
    }
  }
}

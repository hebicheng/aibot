package com.aibot.purchase.service;

import com.aibot.purchase.entity.ChatMessage;
import com.aibot.purchase.entity.Project;
import com.aibot.purchase.entity.ReconciliationReminder;
import com.aibot.purchase.entity.User;
import com.aibot.purchase.mapper.ChatMessageMapper;
import com.aibot.purchase.mapper.ProjectMapper;
import com.aibot.purchase.mapper.ReconciliationReminderMapper;
import com.aibot.purchase.mapper.UserMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 对账单导出提醒调度：
 * <ol>
 *   <li>每分钟扫描到期的用户一次性预约（reconciliation_reminders.status=pending）→
 *       向预约时所在会话推统一导出卡片 → 标 done；项目已删/不可见则标 cancelled 作废，
 *       不再无限重试；</li>
 *   <li>每小时整点匹配用户个人中心配置的周提醒（user_settings.reconciliation.weekday/hour）→
 *       逐项目检查上周（上一个完整周一~周日）是否新增送货单，有则推卡片；空范围不推送。
 *       防重：该项目本周内已推过 weekly 卡片则跳过（服务重启不重复）。</li>
 * </ol>
 */
@Service
public class ReconciliationReminderScheduler {

  private static final Logger log = LoggerFactory.getLogger(ReconciliationReminderScheduler.class);

  private final ReconciliationReminderMapper reminderMapper;
  private final ChatMessageMapper chatMessageMapper;
  private final UserMapper userMapper;
  private final ProjectMapper projectMapper;
  private final ReconciliationChatService reconciliationChatService;
  private final UserSettingsService userSettingsService;
  private final ObjectMapper objectMapper;

  public ReconciliationReminderScheduler(
      ReconciliationReminderMapper reminderMapper,
      ChatMessageMapper chatMessageMapper,
      UserMapper userMapper,
      ProjectMapper projectMapper,
      ReconciliationChatService reconciliationChatService,
      UserSettingsService userSettingsService,
      ObjectMapper objectMapper
  ) {
    this.reminderMapper = reminderMapper;
    this.chatMessageMapper = chatMessageMapper;
    this.userMapper = userMapper;
    this.projectMapper = projectMapper;
    this.reconciliationChatService = reconciliationChatService;
    this.userSettingsService = userSettingsService;
    this.objectMapper = objectMapper;
  }

  /** 到期的一次性预约：到点推统一卡片 */
  @Scheduled(cron = "0 * * * * ?")
  public void fireDueReminders() {
    List<ReconciliationReminder> due = reminderMapper.selectList(
        Wrappers.<ReconciliationReminder>lambdaQuery()
            .eq(ReconciliationReminder::getStatus, "pending")
            .le(ReconciliationReminder::getTriggerAt, LocalDateTime.now()));
    for (ReconciliationReminder reminder : due) {
      try {
        fireOnce(reminder);
      } catch (Throwable ex) {
        // Throwable：编译错误等 Error 也要落日志并保留 pending（下分钟重试），不能静默杀掉调度
        log.warn("对账单预约推送失败 reminder={}", reminder.getId(), ex);
      }
    }
  }

  private void fireOnce(ReconciliationReminder reminder) {
    LocalDate today = LocalDate.now();
    LocalDate start = reminder.getDataStart() != null
        ? reminder.getDataStart() : today.minusWeeks(1).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    LocalDate end = reminder.getDataEnd() != null
        ? reminder.getDataEnd() : today.minusWeeks(1).with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY));
    Map<String, Object> payload;
    try {
      payload = reconciliationChatService.buildCardPayload(
          reminder.getUserId(), reminder.getProjectId(), start, end, "user_reminder");
    } catch (com.aibot.purchase.common.ApiException gone) {
      // 项目已删/不可见（getProject 404）= 永久性失败，预约不可能成功：
      // 标 cancelled 停止每分钟重试，并顺带自愈历史卡死的存量 pending。
      // 其他异常（DB 等瞬时故障）不进这个分支，保留 pending 下分钟重试。
      reminder.setStatus("cancelled");
      reminder.setUpdatedAt(LocalDateTime.now());
      reminderMapper.updateById(reminder);
      log.info("对账单预约作废（项目不可见）reminder={} project={}",
          reminder.getId(), reminder.getProjectId());
      return;
    }
    insertCard(reminder.getUserId(), reminder.getProjectId(), reminder.getBatchId(), payload);
    reminder.setStatus("done");
    reminder.setUpdatedAt(LocalDateTime.now());
    reminderMapper.updateById(reminder);
  }

  /** 周提醒：整点匹配各用户配置的 周几+小时 */
  @Scheduled(cron = "0 0 * * * ?")
  public void weeklyReminder() {
    LocalDateTime now = LocalDateTime.now();
    int weekday = now.getDayOfWeek().getValue();
    int hour = now.getHour();
    List<User> users = userMapper.selectList(Wrappers.<User>lambdaQuery());
    for (User user : users) {
      try {
        UserSettingsService.ReconciliationReminderSettings settings =
            userSettingsService.reconciliationReminder(user.getId());
        if (!settings.enabled() || settings.weekday() != weekday || settings.hour() != hour) {
          continue;
        }
        pushWeeklyCards(user.getId(), now.toLocalDate());
      } catch (Throwable ex) {
        log.warn("对账单周提醒推送失败 user={}", user.getId(), ex);
      }
    }
  }

  private void pushWeeklyCards(String userId, LocalDate today) {
    LocalDate start = today.minusWeeks(1).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    LocalDate end = start.plusDays(6);
    // 防重窗口 = 本周一：只挡本周内已推过的 weekly 卡；窗口若从上周一起算，
    // 会把上周的 weekly 卡也当成重复，导致周提醒只在第一周推一次、之后永远被跳过。
    LocalDate dedupFrom = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    List<Project> projects = projectMapper.selectList(Wrappers.<Project>lambdaQuery()
        .eq(Project::getOwnerId, userId)
        .eq(Project::getDeleted, 0));
    for (Project project : projects) {
      if (weeklyCardAlreadyPushed(userId, project.getId(), dedupFrom)) {
        continue;
      }
      Map<String, Object> payload = reconciliationChatService.buildCardPayload(
          userId, project.getId(), start, end, "weekly");
      @SuppressWarnings("unchecked")
      Map<String, Object> summary = (Map<String, Object>) payload.get("summary");
      if (summary == null) {
        continue; // 无日期汇总即范围内无送货单，不打扰
      }
      Object deliveries = summary.get("deliveries");
      if (deliveries instanceof Number count && count.intValue() == 0) {
        continue; // 空范围不推送
      }
      insertCard(userId, project.getId(), null, payload);
    }
  }

  /** 防重：该项目本周（dedupFrom 起）已推过 weekly 卡片则跳过 */
  private boolean weeklyCardAlreadyPushed(String userId, String projectId, LocalDate dedupFrom) {
    List<ChatMessage> candidates = chatMessageMapper.selectList(
        Wrappers.<ChatMessage>lambdaQuery()
            .eq(ChatMessage::getUserId, userId)
            .eq(ChatMessage::getProjectId, projectId)
            .eq(ChatMessage::getBusinessType, "reconciliation_export")
            .eq(ChatMessage::getSender, "AI")
            .ge(ChatMessage::getCreatedAt, dedupFrom.atStartOfDay()));
    for (ChatMessage message : candidates) {
      if (!StringUtils.hasText(message.getPayload())) {
        continue;
      }
      try {
        Map<String, Object> payload = objectMapper.readValue(message.getPayload(),
            new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
        return "weekly".equals(payload.get("source"));
      } catch (Exception ignored) {
        // 解析失败视为不重复
      }
    }
    return false;
  }

  private void insertCard(String userId, String projectId, String batchId, Map<String, Object> payload) {
    ChatMessage msg = new ChatMessage();
    msg.setUserId(userId);
    msg.setProjectId(projectId);
    msg.setBatchId(batchId);
    msg.setSender("AI");
    msg.setMessageType("CARD");
    msg.setBusinessType("reconciliation_export");
    msg.setContent("对账单导出");
    msg.setStatus("done");
    try {
      msg.setPayload(objectMapper.writeValueAsString(payload));
    } catch (Exception ignored) {
      // 序列化失败留空，卡片降级
    }
    msg.setCreatedAt(LocalDateTime.now());
    chatMessageMapper.insert(msg);
  }
}

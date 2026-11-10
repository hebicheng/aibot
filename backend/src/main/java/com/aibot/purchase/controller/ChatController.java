package com.aibot.purchase.controller;

import com.aibot.purchase.common.ApiResponse;
import com.aibot.purchase.common.CurrentUser;
import com.aibot.purchase.dto.BusinessDtos.ChatAppendResponse;
import com.aibot.purchase.dto.BusinessDtos.ChatClarifyRequest;
import com.aibot.purchase.dto.BusinessDtos.ChatMessageDto;
import com.aibot.purchase.dto.BusinessDtos.ChatSendRequest;
import com.aibot.purchase.service.ChatService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 聊天消息 API（保留消息查询 + 文本/文件上传入口）
 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {
  private final ChatService chatService;

  public ChatController(ChatService chatService) {
    this.chatService = chatService;
  }

  @GetMapping("/messages")
  public ApiResponse<List<ChatMessageDto>> messages(
      @RequestParam(required = false) String projectId,
      @RequestParam(required = false) String batchId
  ) {
    return ApiResponse.ok(chatService.messages(CurrentUser.get().id(), projectId, batchId));
  }

  @PostMapping("/send")
  public ApiResponse<ChatAppendResponse> send(@Valid @RequestBody ChatSendRequest request) {
    return ApiResponse.ok(chatService.send(CurrentUser.get().id(), request));
  }

  @PostMapping("/upload")
  public ApiResponse<ChatAppendResponse> upload(
      @RequestParam("file") MultipartFile file,
      @RequestParam String projectId,
      @RequestParam String batchId,
      @RequestParam(required = false) String guidance
  ) {
    return ApiResponse.ok(chatService.upload(CurrentUser.get().id(), file, projectId, batchId, guidance));
  }

  /**
   * 追问续接：针对「待澄清资料」补充答案，复用已保存的原始附件，无需重新上传。
   */
  @PostMapping("/clarify")
  public ApiResponse<ChatAppendResponse> clarify(@Valid @RequestBody ChatClarifyRequest request) {
    return ApiResponse.ok(chatService.clarify(
        CurrentUser.get().id(), request.documentId(), request.answer()));
  }

  /** 行情待确认卡编辑：用户核对/修正 AI 抽取的行情行后保存，再点「确认导入行情」落库。 */
  @PutMapping("/quote-cards/{messageId}")
  public ApiResponse<Void> updateQuoteCard(
      @PathVariable String messageId,
      @RequestBody Map<String, Object> payload) {
    chatService.updateQuoteCard(CurrentUser.get().id(), messageId, payload);
    return ApiResponse.ok();
  }

  /** 行情卡确认：按核对后的内容落库并刷新受影响批次。 */
  @PostMapping("/quote-cards/{messageId}/confirm")
  public ApiResponse<ChatAppendResponse> confirmQuoteCard(@PathVariable String messageId) {
    return ApiResponse.ok(chatService.confirmQuoteCard(CurrentUser.get().id(), messageId));
  }

  /** 行情卡放弃：归档卡片，行情库不变。 */
  @PostMapping("/quote-cards/{messageId}/discard")
  public ApiResponse<ChatAppendResponse> discardQuoteCard(@PathVariable String messageId) {
    return ApiResponse.ok(chatService.discardQuoteCard(CurrentUser.get().id(), messageId));
  }
}

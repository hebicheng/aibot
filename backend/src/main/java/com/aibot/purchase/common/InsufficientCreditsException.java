package com.aibot.purchase.common;

/**
 * 积分余额不足。由 {@code BillingService.charge} 抛出，调用方据此拦截（聊天）或降级（后台匹配）。
 */
public class InsufficientCreditsException extends RuntimeException {
  public InsufficientCreditsException(String message) {
    super(message);
  }
}

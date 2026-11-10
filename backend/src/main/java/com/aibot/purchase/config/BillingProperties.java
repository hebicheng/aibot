package com.aibot.purchase.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 计费 / 积分配置。
 *
 * <p>换算公式：
 * <pre>
 *   costCredits = round( totalTokens / 1000
 *                       × yuanPerThousandTokens   // 模型真实单价（元/千token），参考千问定价
 *                       × priceMultiplier         // 商业加价倍率（客户无感知，默认 10 倍）
 *                       × creditsPerYuan )         // 积分/元 兑换比（默认 1，即 1 积分=1 元）
 * </pre>
 *
 * <p>代入默认（按当前千问最贵档 qwen3.7-max 输出单价 0.0432 元/千token 兜底，积分与元 1:1）：
 *   1000 token → 0.0432 × 10 × 1 = 0.432 积分，即 1 token ≈ 0.000432 积分（=0.000432 元）。
 *   一次约 1500~3000 token 的聊天扣 0.65~1.45 积分，
 *   月配额 1500 积分（=1500 元）可支撑约 1000~2300 次对话。
 *
 * <p>所有系数均可配置（或未来接入动态参数表），调价对客户无感知。
 */
@ConfigurationProperties(prefix = "billing")
public record BillingProperties(
    double yuanPerThousandTokens,
    double priceMultiplier,
    double creditsPerYuan,
    java.math.BigDecimal monthlyQuota
) {
  public BillingProperties {
    if (yuanPerThousandTokens <= 0) {
      yuanPerThousandTokens = 0.0432; // 千问最贵档 qwen3.7-max 输出单价（元/千token）兜底
    }
    if (priceMultiplier <= 0) {
      priceMultiplier = 10.0; // 商业加价倍率（按真实成本 10 倍计价）
    }
    if (creditsPerYuan <= 0) {
      creditsPerYuan = 1.0; // 积分与元 1:1（1 积分 = 1 元）
    }
    if (monthlyQuota == null || monthlyQuota.compareTo(java.math.BigDecimal.ZERO) <= 0) {
      monthlyQuota = java.math.BigDecimal.valueOf(1500);
    }
  }
}

package com.aibot.purchase.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 材料写法映射（V23，2026-10-29）：单据上的某种写法 → 归一后的材料身份。
 *
 * <p>这是 AI 判同结果的落库形态，也是 rebuild 时优先于正则的依据。同一批次同样的写法
 * 只会问一次 AI，因此也是"不重复计费"的依据。
 */
@Data
@TableName("batch_material_aliases")
public class BatchMaterialAlias {
  @TableId(type = IdType.ASSIGN_UUID)
  private String id;
  private String batchId;
  /** 单据写法规范化文本：材料名|规格|产地（大写、去空格、统一符号）。 */
  private String rawKey;
  private String category;
  private String grade;
  private String diameter;
  private String lengthM;
  private String origin;
  /** AI 给出的规范写法，仅用于人工核对。 */
  private String canonicalName;
  /** ai / rule（AI 不可用时的降级）。 */
  private String source;
  private LocalDateTime createdAt;
  private LocalDateTime updatedAt;
}

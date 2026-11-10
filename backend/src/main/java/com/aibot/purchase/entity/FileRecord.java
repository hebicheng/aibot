package com.aibot.purchase.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("files")
public class FileRecord {
  @TableId(type = IdType.ASSIGN_UUID)
  private String id;
  private String batchId;
  private String name;
  private String fileType;
  private String url;
  private String source;
  private LocalDateTime createdAt;
}

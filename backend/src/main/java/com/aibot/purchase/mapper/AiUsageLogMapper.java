package com.aibot.purchase.mapper;

import com.aibot.purchase.entity.AiUsageLog;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface AiUsageLogMapper extends BaseMapper<AiUsageLog> {

  @Select("SELECT COALESCE(SUM(cost_credits), 0) FROM ai_usage_logs "
      + "WHERE user_id = #{userId} AND created_at >= #{since}")
  java.math.BigDecimal sumCostCreditsSince(@Param("userId") String userId, @Param("since") LocalDateTime since);
}

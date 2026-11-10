package com.aibot.purchase.service;

import com.aibot.purchase.auth.AuthenticatedUser;
import com.aibot.purchase.common.ApiException;
import com.aibot.purchase.dto.AuthDtos.UserProfile;
import com.aibot.purchase.entity.User;
import com.aibot.purchase.mapper.UserMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService {
  private final UserMapper userMapper;
  private final PasswordEncoder passwordEncoder;

  public UserService(UserMapper userMapper, PasswordEncoder passwordEncoder) {
    this.userMapper = userMapper;
    this.passwordEncoder = passwordEncoder;
  }

  public AuthenticatedUser getAuthenticatedUser(String id) {
    User user = userMapper.selectById(id);
    if (user == null) {
      throw new ApiException(HttpStatus.UNAUTHORIZED, "用户不存在");
    }
    return new AuthenticatedUser(
        user.getId(),
        user.getOpenId(),
        user.getNickname(),
        user.getCompanyName(),
        user.getRoleName(),
        null,
        null,
        AuthenticatedUser.isSuperAdminRole(user.getRoleName())
    );
  }

  public UserProfile getProfile(String id) {
    User user = userMapper.selectById(id);
    if (user == null) {
      throw new ApiException(HttpStatus.NOT_FOUND, "用户不存在");
    }
    return mapProfile(user);
  }

  /** 当前请求视角的档案：切换身份时 superAdmin / actingAs 取自超管本人信息。 */
  public UserProfile profileOf(AuthenticatedUser current) {
    User user = userMapper.selectById(current.id());
    if (user == null) {
      throw new ApiException(HttpStatus.NOT_FOUND, "用户不存在");
    }
    UserProfile base = mapProfile(user);
    return new UserProfile(
        base.id(),
        base.nickname(),
        base.avatarUrl(),
        base.companyName(),
        base.roleName(),
        current.superAdmin(),
        current.actingAs()
            ? new UserProfile.ActingAs(current.realUserId(), current.realNickname())
            : null
    );
  }

  public UserProfile authenticate(String username, String password) {
    User user = userMapper.selectOne(Wrappers.<User>lambdaQuery()
        .eq(User::getUsername, username.trim()));
    if (user == null
        || user.getPasswordHash() == null
        || !passwordEncoder.matches(password, user.getPasswordHash())) {
      throw new ApiException(HttpStatus.UNAUTHORIZED, "账号或密码错误");
    }
    return mapProfile(user);
  }

  /**
   * 修改个人资料：只更新传入的字段（null/空白视为不修改）。
   * 昵称是本人显示名（右上角、聊天自称），不回溯历史单据；单位名称用于识别时判断收付款方向。
   */
  @Transactional
  public UserProfile updateProfile(String id, String nickname, String companyName) {
    User user = userMapper.selectById(id);
    if (user == null) {
      throw new ApiException(HttpStatus.NOT_FOUND, "用户不存在");
    }
    if (nickname != null && !nickname.isBlank()) {
      String trimmed = nickname.trim();
      if (trimmed.length() > 20) {
        throw new ApiException(HttpStatus.BAD_REQUEST, "昵称最多 20 个字");
      }
      user.setNickname(trimmed);
    }
    if (companyName != null && !companyName.isBlank()) {
      user.setCompanyName(companyName.trim());
    }
    userMapper.updateById(user);
    return mapProfile(user);
  }

  @Transactional
  public UserProfile upsertWechatUser(String openId, String unionId, String nickname, String avatarUrl) {
    // 账号打通：同一微信开放平台账号下的小程序 openId 与网站应用 openId 不同，
    // 但 unionId 一致。优先按 unionId 关联已有用户，其次按 openId 查，最后新建。
    if (unionId != null && !unionId.isBlank()) {
      User byUnion = userMapper.selectOne(Wrappers.<User>lambdaQuery()
          .eq(User::getUnionId, unionId)
          .last("LIMIT 1"));
      if (byUnion != null) {
        return updateExisting(byUnion, nickname, avatarUrl);
      }
    }
    User existing = userMapper.selectOne(Wrappers.<User>lambdaQuery().eq(User::getOpenId, openId));
    if (existing != null) {
      return updateExisting(existing, nickname, avatarUrl);
    }
    return createUser(openId, unionId, nickname, avatarUrl);
  }

  private UserProfile updateExisting(User existing, String nickname, String avatarUrl) {
    boolean changed = false;
    if (nickname != null && !nickname.isBlank()) {
      existing.setNickname(nickname);
      changed = true;
    }
    if (avatarUrl != null && !avatarUrl.isBlank()) {
      existing.setAvatarUrl(avatarUrl);
      changed = true;
    }
    if (changed) {
      userMapper.updateById(existing);
    }
    return mapProfile(existing);
  }

  private UserProfile createUser(String openId, String unionId, String nickname, String avatarUrl) {
    User user = new User();
    user.setUsername(null);
    user.setPasswordHash(null);
    user.setOpenId(openId);
    user.setUnionId(unionId);
    user.setNickname(nickname == null || nickname.isBlank() ? "采购用户" : nickname);
    user.setAvatarUrl(avatarUrl);
    user.setCompanyName("默认企业");
    user.setRoleName("采购员");
    userMapper.insert(user);
    return mapProfile(user);
  }

  private UserProfile mapProfile(User user) {
    return new UserProfile(
        user.getId(),
        user.getNickname(),
        user.getAvatarUrl(),
        user.getCompanyName(),
        user.getRoleName(),
        AuthenticatedUser.isSuperAdminRole(user.getRoleName()),
        null
    );
  }
}

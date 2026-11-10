package com.aibot.purchase.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.aibot.purchase.common.ApiException;
import com.aibot.purchase.dto.AuthDtos.UserProfile;
import com.aibot.purchase.entity.User;
import com.aibot.purchase.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {
  @Mock
  private UserMapper userMapper;
  @Mock
  private PasswordEncoder passwordEncoder;

  private UserService service;

  @BeforeEach
  void setUp() {
    service = new UserService(userMapper, passwordEncoder);
  }

  @Test
  void authenticatesExistingPasswordUser() {
    User user = new User();
    user.setId("dev_admin");
    user.setUsername("admin");
    user.setPasswordHash("encoded");
    user.setNickname("管理员");
    user.setCompanyName("杭州葭安科技有限公司");
    user.setRoleName("管理员");
    when(userMapper.selectOne(any())).thenReturn(user);
    when(passwordEncoder.matches("admin123", "encoded")).thenReturn(true);

    UserProfile profile = service.authenticate(" admin ", "admin123");

    assertThat(profile.id()).isEqualTo("dev_admin");
    assertThat(profile.companyName()).isEqualTo("杭州葭安科技有限公司");
  }

  @Test
  void rejectsInvalidCredentialsWithoutLeakingWhichFieldFailed() {
    when(userMapper.selectOne(any())).thenReturn(null);

    assertThatThrownBy(() -> service.authenticate("unknown", "wrong-password"))
        .isInstanceOfSatisfying(ApiException.class, exception -> {
          assertThat(exception.status()).isEqualTo(HttpStatus.UNAUTHORIZED);
          assertThat(exception.getMessage()).isEqualTo("账号或密码错误");
        });
  }
}

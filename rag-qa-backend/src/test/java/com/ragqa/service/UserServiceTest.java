package com.ragqa.service;

import com.ragqa.dto.AuthResponse;
import com.ragqa.dto.LoginRequest;
import com.ragqa.dto.RegisterRequest;
import com.ragqa.model.User;
import com.ragqa.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private JwtService jwtService;

    @Mock
    private AuthenticationManager authenticationManager;

    @InjectMocks
    private UserService userService;

    private RegisterRequest registerRequest;
    private LoginRequest loginRequest;

    private static final String TEST_TOKEN = "test.jwt.token";

    @BeforeEach
    void setUp() {
        registerRequest = new RegisterRequest();
        registerRequest.setUsername("testuser");
        registerRequest.setPassword("password123");
        registerRequest.setEmail("test@example.com");

        loginRequest = new LoginRequest();
        loginRequest.setUsername("testuser");
        loginRequest.setPassword("password123");
    }

    @Test
    void shouldRegisterNewUser() {
        // existsByUsername 是生产代码注册路径调用的方法，stub 为 false
        when(userRepository.existsByUsername("testuser")).thenReturn(false);
        when(passwordEncoder.encode("password123")).thenReturn("encoded_password");

        User savedUser = new User();
        savedUser.setId(UUID.randomUUID());
        savedUser.setUsername("testuser");
        savedUser.setPassword("encoded_password");
        savedUser.setEmail("test@example.com");
        when(userRepository.save(any(User.class))).thenReturn(savedUser);

        // register 路径在 save 后会调用 loadUserByUsername + jwtService.generateToken
        when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(savedUser));
        when(jwtService.generateToken(any(UserDetails.class))).thenReturn(TEST_TOKEN);

        AuthResponse response = userService.register(registerRequest);

        assertThat(response).isNotNull();
        assertThat(response.getUsername()).isEqualTo("testuser");
        assertThat(response.getToken()).isEqualTo(TEST_TOKEN);
        verify(userRepository).save(any(User.class));
    }

    @Test
    void shouldThrowExceptionWhenUsernameExists() {
        when(userRepository.existsByUsername("testuser")).thenReturn(true);

        assertThatThrownBy(() -> userService.register(registerRequest))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("用户名已存在");

        verify(userRepository, never()).save(any());
    }

    @Test
    void shouldLoginWithValidCredentials() {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setUsername("testuser");
        user.setPassword("encoded_password");

        // login 路径首先调用 authenticationManager.authenticate
        when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class)))
                .thenReturn(new UsernamePasswordAuthenticationToken("testuser", null, java.util.List.of()));
        when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(user));
        when(jwtService.generateToken(any(UserDetails.class))).thenReturn(TEST_TOKEN);

        AuthResponse response = userService.login(loginRequest);

        assertThat(response).isNotNull();
        assertThat(response.getUsername()).isEqualTo("testuser");
        assertThat(response.getToken()).isEqualTo(TEST_TOKEN);
        verify(authenticationManager).authenticate(any(UsernamePasswordAuthenticationToken.class));
    }

    @Test
    void shouldThrowExceptionWithInvalidPassword() {
        // login 路径首先经过 authenticationManager，模拟密码错误时它抛出 BadCredentialsException
        when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class)))
                .thenThrow(new BadCredentialsException("密码错误"));

        assertThatThrownBy(() -> userService.login(loginRequest))
                .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void shouldThrowExceptionWhenUserNotFound() {
        when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class)))
                .thenThrow(new BadCredentialsException("用户不存在"));

        LoginRequest invalidRequest = new LoginRequest();
        invalidRequest.setUsername("nonexistent");
        invalidRequest.setPassword("password");

        assertThatThrownBy(() -> userService.login(invalidRequest))
                .isInstanceOf(BadCredentialsException.class);
    }
}

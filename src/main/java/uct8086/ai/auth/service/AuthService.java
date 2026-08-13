package uct8086.ai.auth.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import uct8086.ai.auth.entity.AuthUserPrincipal;
import uct8086.ai.auth.entity.UserEntity;
import uct8086.ai.auth.mapper.RoleMapper;
import uct8086.ai.auth.mapper.UserMapper;

import java.util.List;
import java.util.Optional;

/**
 * Authentication logic: verifies username/password and builds the principal.
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final UserMapper userMapper;
    private final RoleMapper roleMapper;
    private final PasswordEncoder passwordEncoder;

    public AuthService(UserMapper userMapper,
                       RoleMapper roleMapper,
                       PasswordEncoder passwordEncoder) {
        this.userMapper = userMapper;
        this.roleMapper = roleMapper;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * Authenticate by username + password. Returns the principal on success, empty on failure.
     */
    public Optional<AuthUserPrincipal> authenticate(String username, String password) {
        if (username == null || password == null) {
            return Optional.empty();
        }
        UserEntity user = userMapper.selectOne(
                Wrappers.<UserEntity>lambdaQuery().eq(UserEntity::getUsername, username));
        if (user == null) {
            return Optional.empty();
        }
        if (!user.isEnabled()) {
            log.warn("Login attempt for disabled user: {}", username);
            return Optional.empty();
        }
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            log.warn("Login failed (bad password) for user: {}", username);
            return Optional.empty();
        }

        List<String> roles = roleMapper.findRoleNamesByUserId(user.getId());
        if (roles == null || roles.isEmpty()) {
            roles = List.of("ROLE_USER");
        }
        return Optional.of(new AuthUserPrincipal(
                user.getId(), user.getUsername(), user.getDisplayName(), roles));
    }
}

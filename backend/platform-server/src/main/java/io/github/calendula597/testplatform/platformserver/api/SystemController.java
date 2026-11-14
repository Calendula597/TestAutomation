package io.github.calendula597.testplatform.platformserver.api;

import io.github.calendula597.testplatform.platformserver.identity.AuditService;
import io.github.calendula597.testplatform.platformserver.identity.User;
import io.github.calendula597.testplatform.platformserver.identity.UserMapper;
import io.github.calendula597.testplatform.platformserver.identity.UserPrincipal;
import io.github.calendula597.testplatform.platformserver.identity.UserRole;
import io.github.calendula597.testplatform.platformserver.identity.UserStatus;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * One-time platform initialization and ADMIN user management.
 *
 * The initialize endpoint is the only path that may create the first
 * account; there is no default or seeded user.
 */
@RestController
@RequestMapping("/api/v1/system")
public class SystemController {

    private static final Pattern USERNAME_PATTERN = Pattern.compile("^[a-zA-Z0-9_.-]{3,64}$");
    private static final int MIN_PASSWORD_LENGTH = 8;

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;
    private final JdbcIndexedSessionRepository sessionRepository;
    private final SecurityContextRepository securityContextRepository;

    public SystemController(UserMapper userMapper, PasswordEncoder passwordEncoder, AuditService auditService,
                            JdbcIndexedSessionRepository sessionRepository,
                            SecurityContextRepository securityContextRepository) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.auditService = auditService;
        this.sessionRepository = sessionRepository;
        this.securityContextRepository = securityContextRepository;
    }

    @GetMapping("/status")
    public IdentityDtos.SystemStatus status() {
        return new IdentityDtos.SystemStatus(userMapper.selectCount(null) > 0);
    }

    @PostMapping("/initialize")
    public synchronized ResponseEntity<?> initialize(@RequestBody IdentityDtos.InitializeRequest body,
                                                     HttpServletRequest request, HttpServletResponse response) {
        if (userMapper.selectCount(null) > 0) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(new IdentityDtos.ErrorResponse(409, "系统已完成初始化，不能重复执行"));
        }
        if (body.username() == null || !USERNAME_PATTERN.matcher(body.username()).matches()) {
            return ResponseEntity.badRequest()
                    .body(new IdentityDtos.ErrorResponse(400, "用户名需为 3-64 位字母、数字或 _ . - 字符"));
        }
        if (body.password() == null || body.password().length() < MIN_PASSWORD_LENGTH) {
            return ResponseEntity.badRequest()
                    .body(new IdentityDtos.ErrorResponse(400, "密码长度不能少于 " + MIN_PASSWORD_LENGTH + " 位"));
        }

        User user = new User();
        user.setUsername(body.username());
        user.setPasswordHash(passwordEncoder.encode(body.password()));
        user.setRole(UserRole.ADMIN.name());
        user.setStatus(UserStatus.ACTIVE.name());
        userMapper.insert(user);

        // Initializing the platform logs the administrator in immediately,
        // so the Vue frontend holds a real server-side session.
        UserPrincipal principal = UserPrincipal.from(user);
        Authentication authentication = UsernamePasswordAuthenticationToken.authenticated(
                principal, null, principal.getAuthorities());
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, request, response);

        auditService.record(authentication, "SYSTEM_INITIALIZE", user, Map.of("role", user.getRole()));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new IdentityDtos.UserView(user.getId(), user.getUsername(), user.getRole(),
                        user.getStatus(), user.getDisplayName()));
    }

    @GetMapping("/users")
    public List<IdentityDtos.UserView> users() {
        return userMapper.selectList(null).stream()
                .map(this::toView)
                .toList();
    }

    /**
     * Creates an additional account; only ADMIN may call this. Credentials
     * are supplied by an administrator, never seeded.
     */
    @PostMapping("/users")
    public ResponseEntity<?> createUser(@RequestBody CreateUserRequest body, Authentication authentication) {
        if (body.username() == null || !USERNAME_PATTERN.matcher(body.username()).matches()) {
            return ResponseEntity.badRequest()
                    .body(new IdentityDtos.ErrorResponse(400, "用户名需为 3-64 位字母、数字或 _ . - 字符"));
        }
        if (body.password() == null || body.password().length() < MIN_PASSWORD_LENGTH) {
            return ResponseEntity.badRequest()
                    .body(new IdentityDtos.ErrorResponse(400, "密码长度不能少于 " + MIN_PASSWORD_LENGTH + " 位"));
        }
        if (userMapper.selectCount(new LambdaQueryWrapper<User>().eq(User::getUsername, body.username())) > 0) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(new IdentityDtos.ErrorResponse(409, "用户名已存在"));
        }

        User user = new User();
        user.setUsername(body.username());
        user.setPasswordHash(passwordEncoder.encode(body.password()));
        user.setRole((body.role() == null ? UserRole.MEMBER : body.role()).name());
        user.setStatus(UserStatus.ACTIVE.name());
        user.setDisplayName(body.displayName());
        userMapper.insert(user);

        auditService.record(authentication, "USER_CREATED", user, Map.of("role", user.getRole()));
        return ResponseEntity.status(HttpStatus.CREATED).body(toView(user));
    }

    public record CreateUserRequest(String username, String password, UserRole role, String displayName) {
    }

    @PutMapping("/users/{id}/status")
    @Transactional
    public ResponseEntity<?> changeStatus(@PathVariable Long id, @RequestBody IdentityDtos.StatusRequest body,
                                          Authentication authentication) {
        User user = userMapper.selectById(id);
        if (user == null) {
            return ResponseEntity.notFound().build();
        }
        UserStatus newStatus = switch (body.status()) {
            case ACTIVE -> UserStatus.ACTIVE;
            case DISABLED -> UserStatus.DISABLED;
        };
        UserStatus previousStatus = UserStatus.valueOf(user.getStatus());
        user.setStatus(newStatus.name());
        userMapper.updateById(user);

        if (newStatus == UserStatus.DISABLED) {
            invalidateSessions(user.getUsername());
        }

        String action = newStatus == UserStatus.DISABLED ? "USER_DISABLED" : "USER_ENABLED";
        auditService.record(authentication, action, user, Map.of(
                "previousStatus", previousStatus.name(),
                "sessionsInvalidated", newStatus == UserStatus.DISABLED));
        return ResponseEntity.ok(toView(user));
    }

    /** Disabling a user must kill every live session for that account. */
    private void invalidateSessions(String username) {
        Map<String, ? extends org.springframework.session.Session> sessions =
                sessionRepository.findByPrincipalName(username);
        sessions.keySet().forEach(sessionRepository::deleteById);
    }

    private IdentityDtos.UserView toView(User user) {
        return new IdentityDtos.UserView(user.getId(), user.getUsername(), user.getRole(),
                user.getStatus(), user.getDisplayName());
    }
}

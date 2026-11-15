package io.github.calendula597.testplatform.platformserver.automation;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.calendula597.testplatform.platformserver.project.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class CiTokenService {
    public static final String TRIGGER = "EXECUTION_TRIGGER";
    public static final String READ = "EXECUTION_READ";
    private static final Set<String> ALLOWED_SCOPES = Set.of(TRIGGER, READ);
    private final CiTokenMapper tokenMapper;
    private final ObjectMapper objectMapper;
    private final SecureRandom random = new SecureRandom();

    public CiTokenService(CiTokenMapper tokenMapper, ObjectMapper objectMapper) {
        this.tokenMapper = tokenMapper;
        this.objectMapper = objectMapper;
    }

    public record IssuedToken(CiToken metadata, String token, List<String> scopes) {
    }

    public IssuedToken issue(Long projectId, String name, List<String> requestedScopes,
                             OffsetDateTime expiresAt, Long createdBy) {
        if (name == null || name.isBlank() || name.trim().length() > 120) {
            throw new IllegalArgumentException("令牌名称不能为空且不超过 120 字");
        }
        if (requestedScopes == null || requestedScopes.isEmpty()
                || !ALLOWED_SCOPES.containsAll(requestedScopes)
                || Set.copyOf(requestedScopes).size() != requestedScopes.size()) {
            throw new IllegalArgumentException("CI 令牌作用域无效或重复");
        }
        if (expiresAt == null || !expiresAt.isAfter(OffsetDateTime.now())
                || expiresAt.isAfter(OffsetDateTime.now().plusDays(365))) {
            throw new IllegalArgumentException("CI 令牌必须在未来过期，且有效期不超过 365 天");
        }
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String hex = HexFormat.of().formatHex(bytes);
        String raw = "cik_" + hex;
        List<String> scopes = requestedScopes.stream().distinct().sorted().toList();
        CiToken token = new CiToken();
        token.setProjectId(projectId);
        token.setName(name.trim());
        token.setTokenHash(hash(raw));
        token.setTokenPrefix("cik_" + hex.substring(0, 8));
        token.setScopes(writeScopes(scopes));
        token.setExpiresAt(expiresAt);
        token.setCreatedBy(createdBy);
        token.setCreatedAt(OffsetDateTime.now());
        tokenMapper.insert(token);
        return new IssuedToken(token, raw, scopes);
    }

    public CiToken authenticate(Long projectId, String requiredScope, String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw new CiException(401, "缺少 CI Bearer 令牌");
        }
        String raw = authorization.substring("Bearer ".length()).trim();
        if (!raw.matches("cik_[0-9a-f]{64}")) {
            throw new CiException(401, "CI 令牌无效或已过期");
        }
        CiToken token = tokenMapper.selectOne(new LambdaQueryWrapper<CiToken>()
                .eq(CiToken::getTokenHash, hash(raw)));
        if (token == null || token.getRevokedAt() != null
                || !token.getExpiresAt().isAfter(OffsetDateTime.now())) {
            throw new CiException(401, "CI 令牌无效或已过期");
        }
        if (!projectId.equals(token.getProjectId())) {
            throw new ResourceNotFoundException("项目不存在");
        }
        if (!scopes(token).contains(requiredScope)) {
            throw new CiException(403, "CI 令牌缺少所需作用域: " + requiredScope);
        }
        token.setLastUsedAt(OffsetDateTime.now());
        tokenMapper.updateById(token);
        return token;
    }

    public List<Map<String, Object>> list(Long projectId) {
        return tokenMapper.selectList(new LambdaQueryWrapper<CiToken>()
                        .eq(CiToken::getProjectId, projectId).orderByAsc(CiToken::getId))
                .stream().map(this::view).toList();
    }

    public void revoke(Long projectId, Long id) {
        CiToken token = tokenMapper.selectById(id);
        if (token == null || !projectId.equals(token.getProjectId())) {
            throw new ResourceNotFoundException("CI 令牌不存在");
        }
        if (token.getRevokedAt() == null) {
            token.setRevokedAt(OffsetDateTime.now());
            tokenMapper.updateById(token);
        }
    }

    public List<String> scopes(CiToken token) {
        try {
            return objectMapper.readValue(token.getScopes(),
                    objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
        } catch (Exception e) {
            throw new IllegalStateException("CI 令牌作用域无法读取", e);
        }
    }

    public Map<String, Object> view(CiToken token) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", token.getId());
        view.put("name", token.getName());
        view.put("prefix", token.getTokenPrefix());
        view.put("scopes", scopes(token));
        view.put("expiresAt", token.getExpiresAt().toString());
        view.put("revokedAt", token.getRevokedAt() == null ? null : token.getRevokedAt().toString());
        view.put("lastUsedAt", token.getLastUsedAt() == null ? null : token.getLastUsedAt().toString());
        view.put("createdAt", token.getCreatedAt().toString());
        return view;
    }

    private String writeScopes(List<String> scopes) {
        try {
            return objectMapper.writeValueAsString(scopes);
        } catch (Exception e) {
            throw new IllegalArgumentException("CI 令牌作用域无法保存", e);
        }
    }

    private String hash(String raw) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}

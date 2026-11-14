package io.github.calendula597.testplatform.platformserver.api;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.calendula597.testplatform.platformserver.environment.Environment;
import io.github.calendula597.testplatform.platformserver.environment.EnvironmentMapper;
import io.github.calendula597.testplatform.platformserver.environment.EnvironmentVersion;
import io.github.calendula597.testplatform.platformserver.environment.EnvironmentVersionMapper;
import io.github.calendula597.testplatform.platformserver.environment.SecretCryptoService;
import io.github.calendula597.testplatform.platformserver.environment.TargetAllowlist;
import io.github.calendula597.testplatform.platformserver.identity.AuditService;
import io.github.calendula597.testplatform.platformserver.identity.UserPrincipal;
import io.github.calendula597.testplatform.platformserver.project.InsufficientRoleException;
import io.github.calendula597.testplatform.platformserver.project.ProjectAccessService;
import io.github.calendula597.testplatform.platformserver.project.ProjectMember;
import io.github.calendula597.testplatform.platformserver.project.ResourceNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Project environments: plain variables, encrypted secret references and
 * target allowlists in versioned snapshots.
 *
 * Secret plaintext crosses this boundary exactly once — on write, where it
 * is immediately encrypted. Every read returns masked values, audit details
 * carry only identifiers and counts, and no Agent tool ever reads
 * environments, so prompts and drafts cannot contain secret material.
 * The validate-target endpoint is the pre-execution gate: executors check
 * every request URL and every redirect hop against it before following.
 */
@RestController
public class EnvironmentController {

    private static final int MAX_ENTRIES = 100;
    private static final int MAX_TARGETS = 50;
    private static final int MAX_VALUE_LENGTH = 10_000;
    private static final String MASKED_VALUE = "******";

    private final ProjectAccessService access;
    private final EnvironmentMapper environmentMapper;
    private final EnvironmentVersionMapper versionMapper;
    private final SecretCryptoService cryptoService;
    private final TargetAllowlist targetAllowlist;
    private final ObjectMapper objectMapper;
    private final AuditService auditService;

    public EnvironmentController(ProjectAccessService access, EnvironmentMapper environmentMapper,
                                 EnvironmentVersionMapper versionMapper, SecretCryptoService cryptoService,
                                 TargetAllowlist targetAllowlist, ObjectMapper objectMapper,
                                 AuditService auditService) {
        this.access = access;
        this.environmentMapper = environmentMapper;
        this.versionMapper = versionMapper;
        this.cryptoService = cryptoService;
        this.targetAllowlist = targetAllowlist;
        this.objectMapper = objectMapper;
        this.auditService = auditService;
    }

    public record EnvironmentUpsert(String name, Map<String, String> plainVariables,
                                    Map<String, String> secrets, List<String> targets) {
    }

    public record EnvironmentUpdate(String name, Map<String, String> plainVariables,
                                    Map<String, String> secretWrites, List<String> targets) {
    }

    public record SecretView(String key, boolean hasValue, String maskedValue) {
    }

    public record EnvironmentView(Long id, Long projectId, String name, int currentVersion,
                                  Map<String, String> plainVariables, List<SecretView> secrets,
                                  List<String> targets, String createdAt) {
    }

    public record EnvironmentSummary(Long id, String name, int currentVersion, String createdAt) {
    }

    public record VersionView(int version, Map<String, String> plainVariables, List<SecretView> secrets,
                              List<String> targets, String createdAt) {
    }

    public record TargetValidationRequest(String url, String redirectUrl) {
    }

    public record TargetValidationResponse(boolean allowed, List<TargetAllowlist.Check> checks) {
    }

    @PostMapping("/api/v1/projects/{projectId}/environments")
    @Transactional
    public ResponseEntity<?> create(@PathVariable Long projectId,
                                    @RequestBody EnvironmentUpsert body,
                                    Authentication authentication) {
        UserPrincipal principal = (UserPrincipal) authentication.getPrincipal();
        access.requireRole(projectId, principal.id(), ProjectMember.ROLE_OWNER);
        if (body.name() == null || body.name().isBlank() || body.name().length() > 100) {
            return badRequest("环境名称不能为空且不超过 100 字");
        }
        String name = body.name().trim();
        Long existing = environmentMapper.selectCount(new LambdaQueryWrapper<Environment>()
                .eq(Environment::getProjectId, projectId)
                .eq(Environment::getName, name));
        if (existing != null && existing > 0) {
            return badRequest("同名环境已存在");
        }

        Map<String, String> plainVariables = normalizeVariables(body.plainVariables());
        if (plainVariables == null) {
            return badRequest("变量键不能为空且值不超过 10000 字");
        }
        Map<String, String> encryptedSecrets = normalizeSecrets(body.secrets());
        if (encryptedSecrets == null) {
            return badRequest("密钥键不能为空、值不能为空且不超过 10000 字");
        }
        if (!encryptedSecrets.isEmpty() && !cryptoService.isConfigured()) {
            return serviceUnavailable("密钥加密未配置主密钥，无法写入密钥");
        }
        encryptedSecrets.replaceAll((key, plaintext) -> cryptoService.encrypt(plaintext));
        List<String> targets = normalizeTargets(body.targets());
        if (targets == null) {
            return badRequest("目标只能是 scheme://host[:port] 形式的 http/https 来源");
        }

        Environment environment = new Environment();
        environment.setProjectId(projectId);
        environment.setName(name);
        environment.setCurrentVersion(1);
        environment.setCreatedBy(principal.id());
        environmentMapper.insert(environment);
        insertVersion(environment, 1, plainVariables, encryptedSecrets, targets, principal.id());

        auditService.record(authentication, "ENVIRONMENT_CREATED", null,
                Map.of("projectId", projectId, "environmentId", environment.getId(),
                        "secretCount", encryptedSecrets.size()));
        return ResponseEntity.status(HttpStatus.CREATED).body(view(environment,
                plainVariables, encryptedSecrets, targets));
    }

    @GetMapping("/api/v1/projects/{projectId}/environments")
    public List<EnvironmentSummary> list(@PathVariable Long projectId, Authentication authentication) {
        UserPrincipal principal = (UserPrincipal) authentication.getPrincipal();
        access.requireRole(projectId, principal.id(), ProjectMember.ROLE_VIEWER);
        return environmentMapper.selectList(new LambdaQueryWrapper<Environment>()
                        .eq(Environment::getProjectId, projectId)
                        .orderByAsc(Environment::getId))
                .stream()
                .map(e -> new EnvironmentSummary(e.getId(), e.getName(), e.getCurrentVersion(),
                        e.getCreatedAt() == null ? null : e.getCreatedAt().toString()))
                .toList();
    }

    @GetMapping("/api/v1/projects/{projectId}/environments/{environmentId}")
    public ResponseEntity<?> detail(@PathVariable Long projectId, @PathVariable Long environmentId,
                                    Authentication authentication) {
        UserPrincipal principal = (UserPrincipal) authentication.getPrincipal();
        access.requireRole(projectId, principal.id(), ProjectMember.ROLE_VIEWER);
        Environment environment = requireEnvironment(projectId, environmentId);
        EnvironmentVersion version = requireVersion(environment);
        return ResponseEntity.ok(view(environment,
                readPlainVariables(version), readSecretCiphertexts(version), readTargets(version)));
    }

    @PutMapping("/api/v1/projects/{projectId}/environments/{environmentId}")
    @Transactional
    public ResponseEntity<?> update(@PathVariable Long projectId, @PathVariable Long environmentId,
                                    @RequestBody EnvironmentUpdate body, Authentication authentication) {
        UserPrincipal principal = (UserPrincipal) authentication.getPrincipal();
        access.requireRole(projectId, principal.id(), ProjectMember.ROLE_OWNER);
        Environment environment = requireEnvironment(projectId, environmentId);
        EnvironmentVersion current = requireVersion(environment);

        Map<String, String> plainVariables = body.plainVariables() != null
                ? normalizeVariables(body.plainVariables())
                : readPlainVariables(current);
        if (plainVariables == null) {
            return badRequest("变量键不能为空且值不超过 10000 字");
        }
        List<String> targets = body.targets() != null
                ? normalizeTargets(body.targets())
                : readTargets(current);
        if (targets == null) {
            return badRequest("目标只能是 scheme://host[:port] 形式的 http/https 来源");
        }

        // Secrets are a delta: null removes the key, a non-blank value
        // replaces it, keys left out keep their stored ciphertext.
        Map<String, String> secrets = readSecretCiphertexts(current);
        if (body.secretWrites() != null) {
            for (Map.Entry<String, String> write : body.secretWrites().entrySet()) {
                String key = write.getKey();
                if (key == null || key.isBlank() || key.length() > 100) {
                    return badRequest("密钥键不能为空且不超过 100 字");
                }
                if (write.getValue() == null) {
                    secrets.remove(key);
                    continue;
                }
                if (write.getValue().isBlank() || write.getValue().length() > MAX_VALUE_LENGTH) {
                    return badRequest("密钥值不能为空且不超过 10000 字");
                }
                if (!cryptoService.isConfigured()) {
                    return serviceUnavailable("密钥加密未配置主密钥，无法写入密钥");
                }
                secrets.put(key, cryptoService.encrypt(write.getValue()));
            }
        }

        int nextVersion = environment.getCurrentVersion() + 1;
        if (body.name() != null) {
            if (body.name().isBlank() || body.name().length() > 100) {
                return badRequest("环境名称不能为空且不超过 100 字");
            }
            String newName = body.name().trim();
            if (!newName.equals(environment.getName())) {
                Long duplicate = environmentMapper.selectCount(
                        new LambdaQueryWrapper<Environment>()
                                .eq(Environment::getProjectId, projectId)
                                .eq(Environment::getName, newName)
                                .ne(Environment::getId, environment.getId()));
                if (duplicate != null && duplicate > 0) {
                    return badRequest("同名环境已存在");
                }
            }
            environment.setName(newName);
        }
        insertVersion(environment, nextVersion, plainVariables, secrets, targets, principal.id());
        environment.setCurrentVersion(nextVersion);
        environmentMapper.updateById(environment);

        auditService.record(authentication, "ENVIRONMENT_UPDATED", null,
                Map.of("environmentId", environment.getId(), "newVersion", nextVersion,
                        "secretCount", secrets.size()));
        return ResponseEntity.ok(view(environment, plainVariables, secrets, targets));
    }

    @GetMapping("/api/v1/projects/{projectId}/environments/{environmentId}/versions")
    public List<VersionView> versions(@PathVariable Long projectId, @PathVariable Long environmentId,
                                      Authentication authentication) {
        UserPrincipal principal = (UserPrincipal) authentication.getPrincipal();
        access.requireRole(projectId, principal.id(), ProjectMember.ROLE_VIEWER);
        Environment environment = requireEnvironment(projectId, environmentId);
        return versionMapper.selectList(new LambdaQueryWrapper<EnvironmentVersion>()
                        .eq(EnvironmentVersion::getEnvironmentId, environment.getId())
                        .orderByAsc(EnvironmentVersion::getVersion))
                .stream()
                .map(v -> new VersionView(v.getVersion(), readPlainVariables(v),
                        maskedSecrets(readSecretCiphertexts(v)), readTargets(v),
                        v.getCreatedAt() == null ? null : v.getCreatedAt().toString()))
                .toList();
    }

    /**
     * Pre-execution target gate. Every request URL and every redirect hop
     * must pass here before an executor follows it; a redirect that leaves
     * the allowlist fails the whole check.
     */
    @PostMapping("/api/v1/projects/{projectId}/environments/{environmentId}/validate-target")
    public ResponseEntity<?> validateTarget(@PathVariable Long projectId, @PathVariable Long environmentId,
                                            @RequestBody TargetValidationRequest body,
                                            Authentication authentication) {
        UserPrincipal principal = (UserPrincipal) authentication.getPrincipal();
        access.requireRole(projectId, principal.id(), ProjectMember.ROLE_MEMBER);
        Environment environment = requireEnvironment(projectId, environmentId);
        EnvironmentVersion version = requireVersion(environment);

        if (body.url() == null || body.url().isBlank()) {
            return badRequest("待校验 URL 不能为空");
        }
        List<String> targets = readTargets(version);
        List<TargetAllowlist.Check> checks = new ArrayList<>();
        try {
            checks.add(targetAllowlist.check(targets, body.url()));
            if (body.redirectUrl() != null && !body.redirectUrl().isBlank()) {
                checks.add(targetAllowlist.check(targets, body.redirectUrl()));
            }
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        }
        boolean allowed = checks.stream().allMatch(TargetAllowlist.Check::allowed);
        return ResponseEntity.ok(new TargetValidationResponse(allowed, checks));
    }

    private Environment requireEnvironment(Long projectId, Long environmentId) {
        Environment environment = environmentMapper.selectById(environmentId);
        if (environment == null || !projectId.equals(environment.getProjectId())) {
            throw new ResourceNotFoundException("环境不存在");
        }
        return environment;
    }

    private EnvironmentVersion requireVersion(Environment environment) {
        EnvironmentVersion version = versionMapper.selectOne(new LambdaQueryWrapper<EnvironmentVersion>()
                .eq(EnvironmentVersion::getEnvironmentId, environment.getId())
                .eq(EnvironmentVersion::getVersion, environment.getCurrentVersion()));
        if (version == null) {
            throw new IllegalStateException("环境版本数据缺失");
        }
        return version;
    }

    private void insertVersion(Environment environment, int version, Map<String, String> plainVariables,
                               Map<String, String> secrets, List<String> targets, Long userId) {
        EnvironmentVersion row = new EnvironmentVersion();
        row.setEnvironmentId(environment.getId());
        row.setVersion(version);
        row.setPlainVariables(writeJson(plainVariables));
        row.setSecrets(writeJson(secrets));
        row.setTargets(writeJson(targets));
        row.setCreatedBy(userId);
        versionMapper.insert(row);
    }

    private EnvironmentView view(Environment environment, Map<String, String> plainVariables,
                                 Map<String, String> secretCiphertexts, List<String> targets) {
        return new EnvironmentView(environment.getId(), environment.getProjectId(), environment.getName(),
                environment.getCurrentVersion(), plainVariables, maskedSecrets(secretCiphertexts),
                targets, environment.getCreatedAt() == null ? null : environment.getCreatedAt().toString());
    }

    /** Masked projection: key names are identifiers, values never leave the crypto boundary. */
    private List<SecretView> maskedSecrets(Map<String, String> secretCiphertexts) {
        return secretCiphertexts.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> new SecretView(e.getKey(), true, MASKED_VALUE))
                .toList();
    }

    private Map<String, String> normalizeVariables(Map<String, String> input) {
        return normalizePlain(input);
    }

    private Map<String, String> normalizeSecrets(Map<String, String> input) {
        if (input == null) {
            return new TreeMap<>();
        }
        for (Map.Entry<String, String> entry : input.entrySet()) {
            if (entry.getValue() == null || entry.getValue().isBlank()) {
                return null;
            }
        }
        return normalizePlain(input);
    }

    private Map<String, String> normalizePlain(Map<String, String> input) {
        if (input == null) {
            return new TreeMap<>();
        }
        if (input.size() > MAX_ENTRIES) {
            return null;
        }
        Map<String, String> normalized = new TreeMap<>();
        for (Map.Entry<String, String> entry : input.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (key == null || key.isBlank() || key.length() > 100) {
                return null;
            }
            if (value == null || value.length() > MAX_VALUE_LENGTH) {
                return null;
            }
            normalized.put(key.trim(), value);
        }
        return normalized;
    }

    private List<String> normalizeTargets(List<String> input) {
        if (input == null) {
            return List.of();
        }
        if (input.size() > MAX_TARGETS) {
            return null;
        }
        List<String> normalized = new ArrayList<>();
        try {
            for (String raw : input) {
                String target = targetAllowlist.normalizeTarget(raw);
                if (!normalized.contains(target)) {
                    normalized.add(target);
                }
            }
        } catch (IllegalArgumentException e) {
            return null;
        }
        return normalized;
    }

    private Map<String, String> readPlainVariables(EnvironmentVersion version) {
        return readJson(version.getPlainVariables());
    }

    private Map<String, String> readSecretCiphertexts(EnvironmentVersion version) {
        return readJson(version.getSecrets());
    }

    private List<String> readTargets(EnvironmentVersion version) {
        try {
            return objectMapper.readValue(version.getTargets(),
                    objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
        } catch (Exception e) {
            throw new IllegalStateException("环境目标数据反序列化失败", e);
        }
    }

    private Map<String, String> readJson(String json) {
        try {
            return objectMapper.readValue(json,
                    objectMapper.getTypeFactory().constructMapType(LinkedHashMap.class, String.class, String.class));
        } catch (Exception e) {
            throw new IllegalStateException("环境快照反序列化失败", e);
        }
    }

    private String writeJson(Object value) {
        return objectMapper.writeValueAsString(value);
    }

    @ExceptionHandler({ResourceNotFoundException.class, InsufficientRoleException.class})
    public ResponseEntity<?> mapped(RuntimeException e) {
        if (e instanceof ResourceNotFoundException) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(new IdentityDtos.ErrorResponse(404, e.getMessage()));
        }
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(new IdentityDtos.ErrorResponse(403, e.getMessage()));
    }

    private ResponseEntity<?> badRequest(String message) {
        return ResponseEntity.badRequest().body(new IdentityDtos.ErrorResponse(400, message));
    }

    private ResponseEntity<?> serviceUnavailable(String message) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new IdentityDtos.ErrorResponse(503, message));
    }
}

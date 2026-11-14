package io.github.calendula597.testplatform.platformserver.api;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.calendula597.testplatform.platformserver.identity.AuditService;
import io.github.calendula597.testplatform.platformserver.identity.UserPrincipal;
import io.github.calendula597.testplatform.platformserver.knowledge.EmbeddingClient;
import io.github.calendula597.testplatform.platformserver.knowledge.KnowledgeChunk;
import io.github.calendula597.testplatform.platformserver.knowledge.KnowledgeChunkMapper;
import io.github.calendula597.testplatform.platformserver.knowledge.KnowledgeChunker;
import io.github.calendula597.testplatform.platformserver.knowledge.KnowledgeDocument;
import io.github.calendula597.testplatform.platformserver.knowledge.KnowledgeDocumentMapper;
import io.github.calendula597.testplatform.platformserver.knowledge.KnowledgeVersion;
import io.github.calendula597.testplatform.platformserver.knowledge.KnowledgeVersionMapper;
import io.github.calendula597.testplatform.platformserver.project.InsufficientRoleException;
import io.github.calendula597.testplatform.platformserver.project.ProjectAccessService;
import io.github.calendula597.testplatform.platformserver.project.ProjectMember;
import io.github.calendula597.testplatform.platformserver.project.ResourceNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Knowledge documents and scoped hybrid retrieval. Scope filtering runs
 * before any retrieval; documents are treated as untrusted data.
 */
@RestController
public class KnowledgeController {

    private final ProjectAccessService access;
    private final KnowledgeDocumentMapper documentMapper;
    private final KnowledgeVersionMapper versionMapper;
    private final KnowledgeChunkMapper chunkMapper;
    private final KnowledgeChunker chunker;
    private final EmbeddingClient embeddingClient;
    private final JdbcTemplate jdbcTemplate;
    private final AuditService auditService;

    public KnowledgeController(ProjectAccessService access, KnowledgeDocumentMapper documentMapper,
                               KnowledgeVersionMapper versionMapper, KnowledgeChunkMapper chunkMapper,
                               KnowledgeChunker chunker, EmbeddingClient embeddingClient,
                               JdbcTemplate jdbcTemplate, AuditService auditService) {
        this.access = access;
        this.documentMapper = documentMapper;
        this.versionMapper = versionMapper;
        this.chunkMapper = chunkMapper;
        this.chunker = chunker;
        this.embeddingClient = embeddingClient;
        this.jdbcTemplate = jdbcTemplate;
        this.auditService = auditService;
    }

    public record KnowledgeUpsert(String title, String body) {
    }

    public record DocumentView(Long id, String title, int currentVersion, String scope, Long projectId) {
    }

    public record VersionView(int version, String body, String createdAt) {
    }

    public record SearchResult(long documentId, String documentTitle, int version, int chunkIndex,
                               String heading, String content, String scope, Long projectId,
                               double score, String matchedBy) {
    }

    @PostMapping("/api/v1/knowledge/global")
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public ResponseEntity<?> createGlobal(@RequestBody KnowledgeUpsert body, Authentication authentication) {
        return create(body, null, authentication, "KNOWLEDGE_GLOBAL_CREATED");
    }

    @GetMapping("/api/v1/knowledge/global")
    public List<DocumentView> listGlobal(Authentication authentication) {
        return documentMapper.selectList(new LambdaQueryWrapper<KnowledgeDocument>()
                        .eq(KnowledgeDocument::getScope, KnowledgeDocument.SCOPE_GLOBAL)
                        .orderByDesc(KnowledgeDocument::getId))
                .stream()
                .map(this::view)
                .toList();
    }

    @PutMapping("/api/v1/knowledge/global/{documentId}")
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public ResponseEntity<?> updateGlobal(@PathVariable Long documentId, @RequestBody KnowledgeUpsert body,
                                          Authentication authentication) {
        KnowledgeDocument document = requireDocument(documentId);
        if (!KnowledgeDocument.SCOPE_GLOBAL.equals(document.getScope())) {
            throw new ResourceNotFoundException("文档不存在");
        }
        return update(document, body, authentication, "KNOWLEDGE_GLOBAL_UPDATED");
    }

    @PostMapping("/api/v1/projects/{projectId}/knowledge")
    @Transactional
    public ResponseEntity<?> createProjectKnowledge(@PathVariable Long projectId,
                                                    @RequestBody KnowledgeUpsert body,
                                                    Authentication authentication) {
        UserPrincipal principal = (UserPrincipal) authentication.getPrincipal();
        access.requireRole(projectId, principal.id(), ProjectMember.ROLE_MEMBER);
        return create(body, projectId, authentication, "KNOWLEDGE_PROJECT_CREATED");
    }

    @GetMapping("/api/v1/projects/{projectId}/knowledge")
    public List<DocumentView> listProjectKnowledge(@PathVariable Long projectId, Authentication authentication) {
        UserPrincipal principal = (UserPrincipal) authentication.getPrincipal();
        access.requireRole(projectId, principal.id(), ProjectMember.ROLE_VIEWER);
        return documentMapper.selectList(new LambdaQueryWrapper<KnowledgeDocument>()
                        .eq(KnowledgeDocument::getScope, KnowledgeDocument.SCOPE_PROJECT)
                        .eq(KnowledgeDocument::getProjectId, projectId)
                        .orderByDesc(KnowledgeDocument::getId))
                .stream()
                .map(this::view)
                .toList();
    }

    @GetMapping("/api/v1/knowledge/global/{documentId}/versions")
    public List<VersionView> globalVersions(@PathVariable Long documentId, Authentication authentication) {
        KnowledgeDocument document = requireDocument(documentId);
        if (!KnowledgeDocument.SCOPE_GLOBAL.equals(document.getScope())) {
            throw new ResourceNotFoundException("文档不存在");
        }
        return versions(document);
    }

    @GetMapping("/api/v1/projects/{projectId}/knowledge/{documentId}/versions")
    public List<VersionView> projectVersions(@PathVariable Long projectId, @PathVariable Long documentId,
                                             Authentication authentication) {
        UserPrincipal principal = (UserPrincipal) authentication.getPrincipal();
        access.requireRole(projectId, principal.id(), ProjectMember.ROLE_VIEWER);
        KnowledgeDocument document = requireDocument(documentId);
        if (!projectId.equals(document.getProjectId())) {
            throw new ResourceNotFoundException("文档不存在");
        }
        return versions(document);
    }

    /**
     * Scoped hybrid search: the authorized scope (global + member projects)
     * is applied to the SQL queries themselves — vector and keyword paths
     * can never see out-of-scope chunks.
     */
    @GetMapping("/api/v1/knowledge/search")
    public ResponseEntity<?> search(@RequestParam("q") String query,
                                    @RequestParam(value = "projectId", required = false) Long projectId,
                                    Authentication authentication) {
        UserPrincipal principal = (UserPrincipal) authentication.getPrincipal();
        if (query == null || query.isBlank()) {
            return badRequest("检索词不能为空");
        }
        List<Long> projectIds = new ArrayList<>();
        if (projectId != null) {
            access.requireRole(projectId, principal.id(), ProjectMember.ROLE_VIEWER);
            projectIds.add(projectId);
        } else {
            projectIds.addAll(accessibleProjectIds(principal.id()));
        }

        List<SearchResult> results = new ArrayList<>();
        // Keyword pass — scoped by construction.
        List<KnowledgeChunk> keywordChunks = keywordChunks(query, projectIds);
        for (KnowledgeChunk chunk : keywordChunks) {
            DocumentMeta meta = metaOf(chunk);
            results.add(new SearchResult(meta.documentId(), meta.title(), meta.version(),
                    chunk.getChunkIndex(), chunk.getHeading(), chunk.getContent(),
                    meta.scope(), meta.projectId(), 1.0, "KEYWORD"));
        }

        String embeddingStatus = "NOT_CONFIGURED";
        String embeddingError = null;
        if (embeddingClient.isConfigured()) {
            try {
                EmbeddingClient.EmbeddingResult embedding = embeddingClient.embed(List.of(query)).orElseThrow();
                double[] queryVector = toDouble(embedding.vectors().get(0));
                List<KnowledgeChunk> vectorChunks = vectorChunks(queryVector, embedding.model(), projectIds);
                for (KnowledgeChunk chunk : vectorChunks) {
                    DocumentMeta meta = metaOf(chunk);
                    if (results.stream().anyMatch(r -> sameChunk(r, meta, chunk))) {
                        continue;
                    }
                    results.add(new SearchResult(meta.documentId(), meta.title(), meta.version(),
                            chunk.getChunkIndex(), chunk.getHeading(), chunk.getContent(),
                            meta.scope(), meta.projectId(), similarity(queryVector, chunk), "VECTOR"));
                }
                embeddingStatus = "OK";
            } catch (EmbeddingClient.EmbeddingException e) {
                embeddingStatus = "ERROR";
                embeddingError = e.getMessage();
            }
        }

        Map<String, Object> response = new java.util.HashMap<>();
        response.put("results", results);
        response.put("embeddingStatus", embeddingStatus);
        response.put("embeddingError", embeddingError);
        return ResponseEntity.ok(response);
    }

    private record DocumentMeta(long documentId, String title, int version, String scope, Long projectId) {
    }

    private DocumentMeta metaOf(KnowledgeChunk chunk) {
        KnowledgeVersion version = versionMapper.selectById(chunk.getVersionId());
        KnowledgeDocument document = documentMapper.selectById(version.getDocumentId());
        return new DocumentMeta(document.getId(), document.getTitle(), version.getVersion(),
                document.getScope(), document.getProjectId());
    }

    private boolean sameChunk(SearchResult result, DocumentMeta meta, KnowledgeChunk chunk) {
        return result.documentId() == meta.documentId() && result.version() == meta.version()
                && result.chunkIndex() == chunk.getChunkIndex();
    }

    private List<KnowledgeChunk> keywordChunks(String query, List<Long> projectIds) {
        String like = "%" + query.replace("%", "\\%") + "%";
        if (projectIds.isEmpty()) {
            return chunkMapper.selectList(new LambdaQueryWrapper<KnowledgeChunk>()
                    .apply("content ILIKE {0}", like)
                    .orderByAsc(KnowledgeChunk::getId)
                    .last("LIMIT 20"))
                    .stream()
                    .filter(c -> scopeOf(c) != null)
                    .toList();
        }
        return chunkMapper.selectList(new LambdaQueryWrapper<KnowledgeChunk>()
                .apply("content ILIKE {0}", like)
                .apply("version_id IN (SELECT kv.id FROM knowledge_version kv "
                        + "JOIN knowledge_document kd ON kd.id = kv.document_id "
                        + "WHERE kd.scope = 'GLOBAL' OR kd.project_id IN ("
                        + projectIds.stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse("-1")
                        + "))")
                .orderByAsc(KnowledgeChunk::getId)
                .last("LIMIT 20"))
                .stream()
                .toList();
    }

    private Long scopeOf(KnowledgeChunk chunk) {
        // Global-only fallback for the unscoped keyword pass.
        KnowledgeVersion version = versionMapper.selectById(chunk.getVersionId());
        KnowledgeDocument document = documentMapper.selectById(version.getDocumentId());
        return KnowledgeDocument.SCOPE_GLOBAL.equals(document.getScope()) ? 0L : null;
    }

    @SuppressWarnings("unchecked")
    private List<KnowledgeChunk> vectorChunks(double[] queryVector, String model, List<Long> projectIds) {
        String vectorLiteral = arrayLiteral(queryVector);
        String projectFilter = projectIds.isEmpty()
                ? "kd.scope = 'GLOBAL'"
                : "(kd.scope = 'GLOBAL' OR kd.project_id IN ("
                        + projectIds.stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse("-1")
                        + "))";
        String sql = "SELECT kc.id FROM knowledge_chunk kc "
                + "JOIN knowledge_version kv ON kv.id = kc.version_id "
                + "JOIN knowledge_document kd ON kd.id = kv.document_id "
                + "WHERE kc.indexed = true AND kc.embedding_model = ? AND " + projectFilter + " "
                + "ORDER BY kc.embedding <=> ?::vector LIMIT 20";
        List<Long> ids = jdbcTemplate.queryForList(sql, Long.class, model, vectorLiteral);
        return ids.isEmpty() ? List.of() : chunkMapper.selectBatchIds(ids);
    }

    private double similarity(double[] queryVector, KnowledgeChunk chunk) {
        try {
            Double distance = jdbcTemplate.queryForObject(
                    "SELECT embedding <=> ?::vector FROM knowledge_chunk WHERE id = ?",
                    Double.class, arrayLiteral(queryVector), chunk.getId());
            return distance == null ? 0.0 : 1.0 - distance;
        } catch (Exception e) {
            return 0.0;
        }
    }

    private String arrayLiteral(double[] vector) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                sb.append(",");
            }
            sb.append(vector[i]);
        }
        return sb.append("]").toString();
    }

    private double[] toDouble(float[] vector) {
        double[] result = new double[vector.length];
        for (int i = 0; i < vector.length; i++) {
            result[i] = vector[i];
        }
        return result;
    }

    private List<Long> accessibleProjectIds(Long userId) {
        return jdbcTemplate.queryForList(
                "SELECT project_id FROM project_member WHERE user_id = ?", Long.class, userId);
    }

    private ResponseEntity<?> create(KnowledgeUpsert body, Long projectId, Authentication authentication,
                                     String action) {
        UserPrincipal principal = (UserPrincipal) authentication.getPrincipal();
        ResponseEntity<?> validation = validate(body);
        if (validation != null) {
            return validation;
        }
        KnowledgeDocument document = new KnowledgeDocument();
        document.setScope(projectId == null ? KnowledgeDocument.SCOPE_GLOBAL : KnowledgeDocument.SCOPE_PROJECT);
        document.setProjectId(projectId);
        document.setTitle(body.title().trim());
        document.setCurrentVersion(1);
        document.setCreatedBy(principal.id());
        documentMapper.insert(document);
        insertVersion(document.getId(), 1, body.body(), principal.id());

        Map<String, Object> detail = new java.util.HashMap<>();
        if (projectId != null) {
            detail.put("projectId", projectId);
        }
        detail.put("documentId", document.getId());
        detail.put("version", 1);
        auditService.record(authentication, action, null, detail);
        return ResponseEntity.status(HttpStatus.CREATED).body(view(document));
    }

    private ResponseEntity<?> update(KnowledgeDocument document, KnowledgeUpsert body,
                                     Authentication authentication, String action) {
        ResponseEntity<?> validation = validate(body);
        if (validation != null) {
            return validation;
        }
        int nextVersion = document.getCurrentVersion() + 1;
        insertVersion(document.getId(), nextVersion, body.body(), currentUserId(authentication));
        document.setTitle(body.title().trim());
        document.setCurrentVersion(nextVersion);
        documentMapper.updateById(document);

        auditService.record(authentication, action, null,
                Map.of("documentId", document.getId(), "newVersion", nextVersion));
        return ResponseEntity.ok(view(document));
    }

    private Long currentUserId(Authentication authentication) {
        return ((UserPrincipal) authentication.getPrincipal()).id();
    }

    private void insertVersion(Long documentId, int version, String body, Long userId) {
        KnowledgeVersion knowledgeVersion = new KnowledgeVersion();
        knowledgeVersion.setDocumentId(documentId);
        knowledgeVersion.setVersion(version);
        knowledgeVersion.setBody(body);
        knowledgeVersion.setCreatedBy(userId);
        versionMapper.insert(knowledgeVersion);

        // Chunk the new version. Embedding requires a configured provider:
        // without one the chunks stay unindexed with a visible error.
        List<KnowledgeChunker.Chunk> chunks = chunker.chunk(body);
        String indexError = embeddingClient.isConfigured() ? null : "嵌入服务未配置";
        for (KnowledgeChunker.Chunk chunk : chunks) {
            KnowledgeChunk row = new KnowledgeChunk();
            row.setVersionId(knowledgeVersion.getId());
            row.setChunkIndex(chunk.index());
            row.setHeading(chunk.heading());
            row.setContent(chunk.content());
            row.setIndexed(false);
            row.setIndexError(indexError);
            chunkMapper.insert(row);
        }
        if (embeddingClient.isConfigured()) {
            embedChunks(knowledgeVersion.getId(), chunks);
        }
    }

    private void embedChunks(Long versionId, List<KnowledgeChunker.Chunk> chunks) {
        try {
            List<String> texts = chunks.stream().map(KnowledgeChunker.Chunk::content).toList();
            EmbeddingClient.EmbeddingResult embedding = embeddingClient.embed(texts).orElseThrow();
            for (int i = 0; i < chunks.size(); i++) {
                KnowledgeChunker.Chunk chunk = chunks.get(i);
                jdbcTemplate.update(
                        "UPDATE knowledge_chunk SET embedding = ?::vector, embedding_model = ?, "
                                + "indexed = true, index_error = NULL WHERE version_id = ? AND chunk_index = ?",
                        arrayLiteral(toDouble(embedding.vectors().get(i))), embedding.model(), versionId,
                        chunk.index());
            }
        } catch (EmbeddingClient.EmbeddingException e) {
            jdbcTemplate.update(
                    "UPDATE knowledge_chunk SET index_error = ? WHERE version_id = ? AND indexed = false",
                    e.getMessage(), versionId);
        }
    }

    private ResponseEntity<?> validate(KnowledgeUpsert body) {
        if (body.title() == null || body.title().isBlank() || body.title().length() > 200) {
            return badRequest("知识标题不能为空且不超过 200 字");
        }
        if (body.body() == null || body.body().isBlank()) {
            return badRequest("知识内容不能为空");
        }
        if (body.body().length() > 500_000) {
            return badRequest("知识内容过长");
        }
        return null;
    }

    private KnowledgeDocument requireDocument(Long documentId) {
        KnowledgeDocument document = documentMapper.selectById(documentId);
        if (document == null) {
            throw new ResourceNotFoundException("知识文档不存在");
        }
        return document;
    }

    private List<VersionView> versions(KnowledgeDocument document) {
        return versionMapper.selectList(new LambdaQueryWrapper<KnowledgeVersion>()
                        .eq(KnowledgeVersion::getDocumentId, document.getId())
                        .orderByAsc(KnowledgeVersion::getVersion))
                .stream()
                .map(v -> new VersionView(v.getVersion(), v.getBody(),
                        v.getCreatedAt() == null ? null : v.getCreatedAt().toString()))
                .toList();
    }

    private DocumentView view(KnowledgeDocument document) {
        return new DocumentView(document.getId(), document.getTitle(), document.getCurrentVersion(),
                document.getScope(), document.getProjectId());
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
}

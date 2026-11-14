package io.github.calendula597.testplatform.platformserver.identity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

/**
 * Writes sensitive-operation audit records. Detail payloads must already be
 * free of credentials and tokens; this service never receives them.
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final AuditEventMapper auditEventMapper;
    private final ObjectMapper objectMapper;

    AuditService(AuditEventMapper auditEventMapper, ObjectMapper objectMapper) {
        this.auditEventMapper = auditEventMapper;
        this.objectMapper = objectMapper;
    }

    public void record(Authentication actor, String action, User target, Map<String, Object> detail) {
        AuditEvent event = new AuditEvent();
        if (actor != null) {
            event.setActorUsername(actor.getName());
        }
        event.setAction(action);
        if (target != null) {
            event.setTargetUserId(target.getId());
            event.setTargetUsername(target.getUsername());
        }
        if (detail != null && !detail.isEmpty()) {
            try {
                event.setDetail(objectMapper.writeValueAsString(detail));
            } catch (Exception e) {
                log.warn("Audit detail serialization failed for action {}", action, e);
                event.setDetail("{\"serializationError\":true}");
            }
        }
        auditEventMapper.insert(event);
    }
}

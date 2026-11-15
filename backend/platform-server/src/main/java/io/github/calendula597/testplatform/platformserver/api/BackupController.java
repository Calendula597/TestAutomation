package io.github.calendula597.testplatform.platformserver.api;

import io.github.calendula597.testplatform.platformserver.reporting.BackupService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class BackupController {

    private final BackupService backupService;

    public BackupController(BackupService backupService) {
        this.backupService = backupService;
    }

    @PostMapping("/api/v1/system/backups")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> create() throws Exception {
        return backupService.createBackup();
    }

    @PostMapping("/api/v1/system/restore")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> restore(@RequestBody Map<String, String> body) throws Exception {
        return backupService.restoreBackup(body.get("filename"));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<?> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(new IdentityDtos.ErrorResponse(400, e.getMessage()));
    }
}

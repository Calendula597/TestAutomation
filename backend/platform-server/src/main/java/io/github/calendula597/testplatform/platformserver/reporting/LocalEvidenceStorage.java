package io.github.calendula597.testplatform.platformserver.reporting;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.UUID;

@Component
public class LocalEvidenceStorage implements EvidenceStorage {

    private final Path root;

    public LocalEvidenceStorage(@Value("${app.evidence.dir:./evidence}") String evidenceDir) {
        this.root = Path.of(evidenceDir).toAbsolutePath().normalize();
    }

    @Override
    public Path createRunDirectory() throws IOException {
        Path runDirectory = root.resolve(UUID.randomUUID().toString());
        Files.createDirectories(runDirectory);
        return runDirectory;
    }

    @Override
    public String keyOf(Path file) {
        Path absolute = file.toAbsolutePath().normalize();
        if (!absolute.startsWith(root)) {
            throw new IllegalArgumentException("证据文件不在存储目录内");
        }
        return root.relativize(absolute).toString().replace(File.separatorChar, '/');
    }

    @Override
    public Resource read(String storageKey) throws IOException {
        if (storageKey == null || storageKey.isBlank()) {
            throw new IOException("证据存储键为空");
        }
        Path relative = Path.of(storageKey);
        if (relative.isAbsolute()) {
            throw new IOException("证据存储键必须是相对路径");
        }
        Path resolved = root.resolve(relative).normalize();
        if (!resolved.startsWith(root) || !Files.isRegularFile(resolved, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("证据文件不存在");
        }
        return new FileSystemResource(resolved);
    }
}

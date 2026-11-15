package io.github.calendula597.testplatform.platformserver.reporting;

import org.springframework.core.io.Resource;

import java.io.IOException;
import java.nio.file.Path;

public interface EvidenceStorage {

    Path createRunDirectory() throws IOException;

    String keyOf(Path file);

    Resource read(String storageKey) throws IOException;
}

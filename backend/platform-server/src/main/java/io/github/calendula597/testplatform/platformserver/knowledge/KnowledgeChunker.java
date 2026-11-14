package io.github.calendula597.testplatform.platformserver.knowledge;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits a knowledge document body into retrievable chunks: markdown
 * headings start new chunks, long paragraphs are split further. Each chunk
 * remembers its heading so search results can point at the source location.
 */
@Service
public class KnowledgeChunker {

    static final int MAX_CHUNK_LENGTH = 800;

    public record Chunk(int index, String heading, String content) {
    }

    public List<Chunk> chunk(String body) {
        List<Chunk> chunks = new ArrayList<>();
        String currentHeading = "";
        StringBuilder current = new StringBuilder();
        int index = 0;

        for (String line : body.split("\n")) {
            String trimmed = line.stripTrailing();
            if (trimmed.startsWith("#")) {
                flush(chunks, index++, currentHeading, current);
                currentHeading = trimmed.replaceAll("^#+\\s*", "").trim();
                continue;
            }
            if (current.length() + line.length() + 1 > MAX_CHUNK_LENGTH) {
                flush(chunks, index++, currentHeading, current);
            }
            current.append(line).append("\n");
        }
        flush(chunks, index, currentHeading, current);
        return chunks;
    }

    private void flush(List<Chunk> chunks, int index, String heading, StringBuilder content) {
        String text = content.toString().strip();
        if (!text.isEmpty()) {
            chunks.add(new Chunk(index, heading, text));
        }
        content.setLength(0);
    }
}

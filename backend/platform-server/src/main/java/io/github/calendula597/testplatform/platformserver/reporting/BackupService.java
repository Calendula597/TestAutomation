package io.github.calendula597.testplatform.platformserver.reporting;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

@Service
public class BackupService {

    private final Path backupDir;
    private final Path evidenceDir;
    private final String jdbcUrl;
    private final String username;
    private final String password;
    private final String postgresImage;

    public BackupService(@Value("${app.backup.dir:./backups}") String backupDir,
                         @Value("${app.evidence.dir:./evidence}") String evidenceDir,
                         @Value("${spring.datasource.url}") String jdbcUrl,
                         @Value("${spring.datasource.username}") String username,
                         @Value("${spring.datasource.password}") String password,
                         @Value("${app.backup.postgres-image:pgvector/pgvector:pg16@sha256:7b822b0aac60967beb1ea5e576b8602c94c300a157d187f385ae3e0da199b90a}")
                         String postgresImage) {
        this.backupDir = Path.of(backupDir).toAbsolutePath().normalize();
        this.evidenceDir = Path.of(evidenceDir).toAbsolutePath().normalize();
        this.jdbcUrl = jdbcUrl;
        this.username = username;
        this.password = password;
        this.postgresImage = postgresImage;
    }

    public Map<String, Object> createBackup() throws Exception {
        Files.createDirectories(backupDir);
        Path sql = backupDir.resolve("dump-" + UUID.randomUUID() + ".sql");
        try {
            runDocker(List.of("pg_dump"), sql);
            Path archive = backupDir.resolve("backup-" + UUID.randomUUID() + ".zip");
            try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(archive.toFile()))) {
                ZipEntry database = new ZipEntry("database.sql");
                database.setTime(Files.getLastModifiedTime(sql).toMillis());
                zip.putNextEntry(database);
                Files.copy(sql, zip);
                zip.closeEntry();
                if (Files.isDirectory(evidenceDir)) {
                    try (var files = Files.walk(evidenceDir)) {
                        for (Path file : files.filter(Files::isRegularFile).toList()) {
                            String name = "evidence/" + evidenceDir.relativize(file).toString()
                                    .replace(File.separatorChar, '/');
                            ZipEntry evidence = new ZipEntry(name);
                            evidence.setTime(Files.getLastModifiedTime(file).toMillis());
                            zip.putNextEntry(evidence);
                            Files.copy(file, zip);
                            zip.closeEntry();
                        }
                    }
                }
            }
            return Map.of("filename", archive.getFileName().toString(), "bytes", Files.size(archive));
        } finally {
            Files.deleteIfExists(sql);
        }
    }

    public Map<String, Object> restoreBackup(String filename) throws Exception {
        if (filename == null || !filename.matches(
                "backup-[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\\.zip")) {
            throw new IllegalArgumentException("备份文件名无效");
        }
        Path archive = backupDir.resolve(filename).normalize();
        if (!archive.startsWith(backupDir) || !Files.isRegularFile(archive)) {
            throw new IllegalArgumentException("备份文件不存在");
        }
        Path restoreDirectory = backupDir.resolve("restore-" + UUID.randomUUID());
        Files.createDirectories(restoreDirectory);
        extract(archive, restoreDirectory);
        String database = "restore_" + UUID.randomUUID().toString().replace("-", "");
        runDocker(List.of("createdb", database), null);
        try {
            runDocker(List.of("psql", "-d", database, "-f", "/backup/database.sql"), null, restoreDirectory);
        } catch (Exception e) {
            try {
                runDocker(List.of("dropdb", database), null);
            } catch (Exception ignored) {
            }
            throw e;
        }
        return Map.of("database", database, "evidenceDir", restoreDirectory.resolve("evidence").toString());
    }

    private void extract(Path archive, Path target) throws IOException {
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                Path destination = target.resolve(entry.getName()).normalize();
                if (!destination.startsWith(target)) {
                    throw new IOException("备份包含非法路径");
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(destination);
                } else {
                    Files.createDirectories(destination.getParent());
                    try (var input = zip.getInputStream(entry)) {
                        Files.copy(input, destination);
                    }
                    if (entry.getTime() >= 0) {
                        Files.setLastModifiedTime(destination,
                                java.nio.file.attribute.FileTime.fromMillis(entry.getTime()));
                    }
                }
            }
        }
    }

    private void runDocker(List<String> command, Path output) throws Exception {
        runDocker(command, output, backupDir);
    }

    private void runDocker(List<String> command, Path output, Path mountDirectory) throws Exception {
        URI uri = URI.create(jdbcUrl.replace("jdbc:", ""));
        List<String> args = new ArrayList<>(List.of("docker", "run", "--rm",
                "--add-host", "host.docker.internal:host-gateway",
                "-e", "PGPASSWORD", "-v", mountDirectory + ":/backup", postgresImage));
        String databaseHost = "host.docker.internal";
        String port = uri.getPort() == -1 ? "5432" : String.valueOf(uri.getPort());
        if ("createdb".equals(command.get(0))) {
            args.addAll(List.of("createdb", "-h", databaseHost, "-p", port, "-U", username, command.get(1)));
        } else if ("dropdb".equals(command.get(0))) {
            args.addAll(List.of("dropdb", "--if-exists", "-h", databaseHost, "-p", port, "-U", username, command.get(1)));
        } else if ("psql".equals(command.get(0))) {
            args.addAll(List.of("psql", "-h", databaseHost, "-p", port, "-U", username,
                    "-v", "ON_ERROR_STOP=1", command.get(1), command.get(2), command.get(3), command.get(4)));
        } else {
            args.addAll(List.of("pg_dump", "-h", databaseHost, "-p", port, "-U", username, uri.getPath().substring(1)));
        }
        Path log = output == null ? backupDir.resolve("backup-command-" + UUID.randomUUID() + ".log") : null;
        ProcessBuilder builder = new ProcessBuilder(args).redirectErrorStream(true)
                .redirectOutput(output == null ? log.toFile() : output.toFile());
        builder.environment().put("PGPASSWORD", password);
        Process process = builder.start();
        process.getOutputStream().close();
        if (!process.waitFor(120, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IOException("数据库备份命令超时");
        }
        if (process.exitValue() != 0) {
            String details = output == null && Files.isRegularFile(log) ? Files.readString(log) : "命令执行失败";
            throw new IOException("数据库备份命令失败: " + details);
        }
        if (log != null) {
            Files.deleteIfExists(log);
        }
    }
}

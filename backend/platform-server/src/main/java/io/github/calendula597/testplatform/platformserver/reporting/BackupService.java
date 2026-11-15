package io.github.calendula597.testplatform.platformserver.reporting;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
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

    public BackupService(@Value("${app.backup.dir:./backups}") String backupDir,
                         @Value("${app.evidence.dir:./evidence}") String evidenceDir,
                         @Value("${spring.datasource.url}") String jdbcUrl,
                         @Value("${spring.datasource.username}") String username,
                         @Value("${spring.datasource.password}") String password) {
        this.backupDir = Path.of(backupDir).toAbsolutePath().normalize();
        this.evidenceDir = Path.of(evidenceDir).toAbsolutePath().normalize();
        this.jdbcUrl = jdbcUrl;
        this.username = username;
        this.password = password;
    }

    public Map<String, Object> createBackup() throws Exception {
        Files.createDirectories(backupDir);
        Path sql = backupDir.resolve("dump-" + UUID.randomUUID() + ".sql");
        try {
            runDatabaseCommand(List.of("pg_dump"), sql);
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
        runDatabaseCommand(List.of("createdb", database), null);
        try {
            runDatabaseCommand(List.of("psql", "-d", database), restoreDirectory.resolve("database.sql"));
        } catch (Exception e) {
            try {
                runDatabaseCommand(List.of("dropdb", database), null);
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

    private String databaseContainer() throws Exception {
        URI uri = URI.create(jdbcUrl.replace("jdbc:", ""));
        String port = uri.getPort() == -1 ? "5432" : String.valueOf(uri.getPort());
        Process process = new ProcessBuilder("docker", "ps", "--format", "{{.ID}} {{.Ports}}")
                .redirectErrorStream(true).start();
        if (!process.waitFor(15, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IOException("查询数据库容器超时");
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        List<String> matches = output.lines().filter(line -> line.contains(":" + port + "->"))
                .map(line -> line.split("\\s+")[0]).filter(id -> id.matches("[0-9a-f]{12,64}")).toList();
        if (process.exitValue() != 0 || matches.size() != 1) {
            throw new IOException("无法唯一定位当前 PostgreSQL 容器，映射端口=" + port + "，结果=" + output);
        }
        return matches.get(0);
    }

    private void runDatabaseCommand(List<String> command, Path sqlFile) throws Exception {
        String container = databaseContainer();
        List<String> args = new ArrayList<>(List.of("docker", "exec", "-e", "PGPASSWORD"));
        if ("psql".equals(command.get(0))) {
            args.add("-i");
        }
        args.add(container);
        String database = URI.create(jdbcUrl.replace("jdbc:", "")).getPath().substring(1);
        switch (command.get(0)) {
            case "pg_dump" -> args.addAll(List.of("pg_dump", "-U", username, database));
            case "createdb" -> args.addAll(List.of("createdb", "-U", username, command.get(1)));
            case "dropdb" -> args.addAll(List.of("dropdb", "--if-exists", "-U", username, command.get(1)));
            case "psql" -> args.addAll(List.of("psql", "-U", username, "-v", "ON_ERROR_STOP=1",
                    command.get(1), command.get(2), "-f", "-"));
            default -> throw new IllegalArgumentException("不支持的数据库备份命令");
        }
        Path log = backupDir.resolve("backup-command-" + UUID.randomUUID() + ".log");
        ProcessBuilder builder = new ProcessBuilder(args);
        if ("pg_dump".equals(command.get(0))) {
            builder.redirectOutput(sqlFile.toFile()).redirectError(log.toFile());
        } else {
            builder.redirectErrorStream(true).redirectOutput(log.toFile());
        }
        if ("psql".equals(command.get(0))) {
            builder.redirectInput(sqlFile.toFile());
        }
        builder.environment().put("PGPASSWORD", password);
        Process process = builder.start();
        if (!process.waitFor(120, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IOException("数据库备份命令超时");
        }
        if (process.exitValue() != 0) {
            String details = Files.isRegularFile(log) ? Files.readString(log) : "命令执行失败";
            throw new IOException("数据库备份命令失败: " + details);
        }
        if ("pg_dump".equals(command.get(0))) {
            try (var lines = Files.lines(sqlFile);
                 var channel = java.nio.channels.FileChannel.open(sqlFile)) {
                if (!lines.limit(2).toList().equals(List.of("--", "-- PostgreSQL database dump"))) {
                    throw new IOException("数据库备份内容不完整");
                }
                java.nio.ByteBuffer tail = java.nio.ByteBuffer.allocate((int) Math.min(256, channel.size()));
                channel.position(channel.size() - tail.capacity());
                while (tail.hasRemaining()) {
                    channel.read(tail);
                }
                if (!new String(tail.array(), StandardCharsets.UTF_8)
                        .contains("PostgreSQL database dump complete")) {
                    throw new IOException("数据库备份内容不完整");
                }
            }
        }
        Files.deleteIfExists(log);
    }
}

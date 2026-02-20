package com.example.accessingdatamysql;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/file")
@Tag(name = "File", description = "File operations under /Users/fernando.karnagi/App/")
public class FileController {

    private static final Path BASE_DIR = Paths.get("/Users/fernando.karnagi/App");

    private Path resolveAndValidate(String filename) {
        Path resolved = BASE_DIR.resolve(filename).normalize();
        if (!resolved.startsWith(BASE_DIR)) {
            throw new IllegalArgumentException("Invalid filename");
        }
        return resolved;
    }

    @Operation(summary = "List all files in the folder")
    @GetMapping
    public ResponseEntity<List<String>> listFiles() {
        try (Stream<Path> stream = Files.list(BASE_DIR)) {
            List<String> files = stream
                    .filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString())
                    .sorted()
                    .collect(Collectors.toList());
            return ResponseEntity.ok(files);
        } catch (IOException e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    @Operation(summary = "Get file content")
    @GetMapping(value = "/{filename}", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> getFileContent(
            @Parameter(description = "Name of the file") @PathVariable String filename) {
        try {
            Path filePath = resolveAndValidate(filename);
            if (!Files.exists(filePath)) {
                return ResponseEntity.notFound().build();
            }
            String content = new String(Files.readAllBytes(filePath), StandardCharsets.UTF_8);
            return ResponseEntity.ok(content);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        } catch (IOException e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    @Operation(summary = "Create a new file with default content")
    @PostMapping("/{filename}")
    public ResponseEntity<String> createFile(
            @Parameter(description = "Name of the file") @PathVariable String filename) {
        try {
            Path filePath = resolveAndValidate(filename);
            if (Files.exists(filePath)) {
                return ResponseEntity.badRequest().body("File already exists");
            }
            Files.write(filePath, "New file".getBytes(StandardCharsets.UTF_8));
            return ResponseEntity.ok("File created");
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body("Invalid filename");
        } catch (IOException e) {
            return ResponseEntity.internalServerError().body("Error creating file: " + e.getMessage());
        }
    }

    @Operation(summary = "Update file content")
    @PutMapping(value = "/{filename}", consumes = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> updateFileContent(
            @Parameter(description = "Name of the file") @PathVariable String filename,
            @RequestBody String content) {
        try {
            Path filePath = resolveAndValidate(filename);
            if (!Files.exists(filePath)) {
                return ResponseEntity.notFound().build();
            }
            Files.write(filePath, content.getBytes(StandardCharsets.UTF_8));
            return ResponseEntity.ok("File updated");
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body("Invalid filename");
        } catch (IOException e) {
            return ResponseEntity.internalServerError().body("Error writing file: " + e.getMessage());
        }
    }

    @Operation(summary = "Delete a file")
    @DeleteMapping("/{filename}")
    public ResponseEntity<String> deleteFile(
            @Parameter(description = "Name of the file") @PathVariable String filename) {
        try {
            Path filePath = resolveAndValidate(filename);
            if (!Files.exists(filePath)) {
                return ResponseEntity.notFound().build();
            }
            Files.delete(filePath);
            return ResponseEntity.ok("File deleted");
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body("Invalid filename");
        } catch (IOException e) {
            return ResponseEntity.internalServerError().body("Error deleting file: " + e.getMessage());
        }
    }
}

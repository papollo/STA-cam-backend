package com.wavestone.stacamback.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wavestone.stacamback.model.DetectionResult;
import com.wavestone.stacamback.repository.DetectionResultRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;

@Service
@RequiredArgsConstructor
@Slf4j
public class YoloProcessingService {

    private final DetectionResultRepository repository;
    private final SimpMessagingTemplate messagingTemplate;
    private final ObjectMapper objectMapper;

    @Value("${app.upload.dir:uploads}")
    private String uploadDir;

    @Value("${app.python.script.path:python_scripts/yolo_processor.py}")
    private String pythonScriptPath;

    public DetectionResult saveUploadedFile(MultipartFile file, String cameraId) throws IOException {
        // Create upload directory if it doesn't exist
        Path uploadPath = Paths.get(uploadDir);
        if (!Files.exists(uploadPath)) {
            Files.createDirectories(uploadPath);
        }

        // Save file
        String fileName = System.currentTimeMillis() + "_" + file.getOriginalFilename();
        Path filePath = uploadPath.resolve(fileName);
        Files.copy(file.getInputStream(), filePath);

        // Determine file type
        String fileType = getFileType(file.getOriginalFilename());

        Integer width = null;
        Integer height = null;
        if (fileType.equals("IMAGE")) {
            try {
                BufferedImage image = ImageIO.read(filePath.toFile());
                if (image != null) {
                    width = image.getWidth();
                    height = image.getHeight();
                }
            } catch (Exception e) {
                log.warn("Could not read image dimensions for file: {}", fileName);
            }
        } else if (fileType.equals("VIDEO")) {
            // TODO: Extract video dimensions using a suitable library (e.g., Xuggler, JCodec, etc.)
        }

        // Create detection result record
        DetectionResult result = new DetectionResult();
        result.setFileName(fileName);
        result.setFileType(fileType);
        result.setFilePath(filePath.toString());
        result.setStatus("PENDING");
        result.setWidth(width);
        result.setHeight(height);
        result.setCameraId(cameraId);

        return repository.save(result);
    }

    public CompletableFuture<DetectionResult> processWithYolo(DetectionResult detectionResult) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                // Update status to processing
                detectionResult.setStatus("PROCESSING");
                repository.save(detectionResult);

                // Broadcast status update
                broadcastDetectionUpdate(detectionResult);

                // Call Python YOLO script - try multiple Python commands
                ProcessBuilder processBuilder = null;
                String[] pythonCommands = {"python", "py", "python3", "python.exe"};

                for (String pythonCmd : pythonCommands) {
                    try {
                        processBuilder = new ProcessBuilder(
                            pythonCmd, pythonScriptPath, detectionResult.getFilePath()
                        );
                        processBuilder.redirectErrorStream(true);

                        // Test if this Python command works
                        ProcessBuilder testBuilder = new ProcessBuilder(pythonCmd, "--version");
                        Process testProcess = testBuilder.start();
                        int testExitCode = testProcess.waitFor();

                        if (testExitCode == 0) {
                            log.info("Using Python command: {}", pythonCmd);
                            break;
                        }
                    } catch (Exception e) {
                        log.warn("Python command '{}' not available: {}", pythonCmd, e.getMessage());
                        continue;
                    }
                }

                if (processBuilder == null) {
                    throw new RuntimeException("Python is not installed or not accessible. Please install Python and ensure it's in your PATH.");
                }

                Process process = processBuilder.start();

                // Read Python script output
                StringBuilder output = new StringBuilder();
                StringBuilder errorOutput = new StringBuilder();

                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream()));
                     BufferedReader errorReader = new BufferedReader(
                        new InputStreamReader(process.getErrorStream()))) {

                    String line;
                    while ((line = reader.readLine()) != null) {
                        output.append(line).append("\n");
                    }

                    String errorLine;
                    while ((errorLine = errorReader.readLine()) != null) {
                        errorOutput.append(errorLine).append("\n");
                    }
                }

                int exitCode = process.waitFor();

                if (exitCode == 0) {
                    // Parse YOLO results from Python output
                    String rawOutput = output.toString().trim();

                    // Extract JSON from output (YOLO may still output some info)
                    String jsonOutput = extractJsonFromOutput(rawOutput);

                    // Validate that we got valid JSON
                    if (jsonOutput != null && jsonOutput.startsWith("{") && jsonOutput.endsWith("}")) {
                        detectionResult.setDetections(jsonOutput);
                        detectionResult.setStatus("COMPLETED");
                        log.info("YOLO processing completed for file: {}", detectionResult.getFileName());
                    } else {
                        detectionResult.setStatus("FAILED");
                        detectionResult.setErrorMessage("Invalid JSON output from Python script: " + rawOutput);
                        log.error("Invalid JSON output for file: {}", detectionResult.getFileName());
                    }
                } else {
                    detectionResult.setStatus("FAILED");
                    String errorMessage = "Python script failed with exit code: " + exitCode +
                                        "\nStdout: " + output.toString() +
                                        "\nStderr: " + errorOutput.toString();
                    detectionResult.setErrorMessage(errorMessage);
                    log.error("YOLO processing failed for file: {} - {}", detectionResult.getFileName(), errorMessage);
                }

            } catch (Exception e) {
                detectionResult.setStatus("FAILED");
                detectionResult.setErrorMessage(e.getMessage());
                log.error("Error processing file with YOLO: {}", detectionResult.getFileName(), e);
            }

            // Save final result
            DetectionResult finalResult = repository.save(detectionResult);

            // Broadcast final update
            broadcastDetectionUpdate(finalResult);

            return finalResult;
        });
    }

    private void broadcastDetectionUpdate(DetectionResult result) {
        try {
            messagingTemplate.convertAndSend("/topic/detections", result);
        } catch (Exception e) {
            log.error("Failed to broadcast detection update", e);
        }
    }

    private String getFileType(String fileName) {
        String extension = fileName.substring(fileName.lastIndexOf(".") + 1).toLowerCase();
        switch (extension) {
            case "jpg":
            case "jpeg":
            case "png":
            case "gif":
            case "bmp":
                return "IMAGE";
            case "mp4":
            case "avi":
            case "mov":
            case "wmv":
            case "mkv":
                return "VIDEO";
            default:
                return "UNKNOWN";
        }
    }

    public List<DetectionResult> getRecentDetections() {
        return repository.findTop10ByOrderByProcessedAtDesc();
    }

    public List<DetectionResult> getDetectionsSince(LocalDateTime since) {
        return repository.findRecentDetections(since);
    }

    /**
     * Extract JSON from Python script output that may contain YOLO debug information
     */
    private String extractJsonFromOutput(String rawOutput) {
        if (rawOutput == null || rawOutput.trim().isEmpty()) {
            return null;
        }

        // Find the first '{' and last '}' to extract JSON
        int firstBrace = rawOutput.indexOf('{');
        int lastBrace = rawOutput.lastIndexOf('}');

        if (firstBrace != -1 && lastBrace != -1 && firstBrace <= lastBrace) {
            return rawOutput.substring(firstBrace, lastBrace + 1);
        }

        return null;
    }
}

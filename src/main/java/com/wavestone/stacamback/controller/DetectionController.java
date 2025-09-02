package com.wavestone.stacamback.controller;

import com.wavestone.stacamback.model.DetectionResult;
import com.wavestone.stacamback.service.YoloProcessingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/detection")
@RequiredArgsConstructor
@Slf4j
@CrossOrigin(originPatterns = "*") // Use originPatterns instead of origins
public class DetectionController {

    private final YoloProcessingService yoloProcessingService;

    @PostMapping("/upload")
    public ResponseEntity<Map<String, Object>> uploadFile(
            @RequestParam("file") MultipartFile file,
            @RequestParam("cameraId") String cameraId) {

        Map<String, Object> response = new HashMap<>();

        try {
            // Validate camera ID
            if (cameraId == null || cameraId.trim().isEmpty()) {
                response.put("error", "Camera ID is required");
                return ResponseEntity.badRequest().body(response);
            }

            if (!isValidCameraId(cameraId)) {
                response.put("error", "Invalid camera ID. Must be 'camera_one' or 'camera_two'");
                return ResponseEntity.badRequest().body(response);
            }

            // Validate file
            if (file.isEmpty()) {
                response.put("error", "File is empty");
                return ResponseEntity.badRequest().body(response);
            }

            // Check file type
            String fileName = file.getOriginalFilename();
            if (fileName == null || !isValidFileType(fileName)) {
                response.put("error", "Invalid file type. Only images and videos are allowed.");
                return ResponseEntity.badRequest().body(response);
            }

            // Save uploaded file with camera information
            DetectionResult detectionResult = yoloProcessingService.saveUploadedFile(file, cameraId);

            // Start YOLO processing asynchronously
            yoloProcessingService.processWithYolo(detectionResult);

            response.put("success", true);
            response.put("message", "File uploaded successfully and processing started");
            response.put("detectionId", detectionResult.getId());
            response.put("fileName", detectionResult.getFileName());
            response.put("cameraId", detectionResult.getCameraId());
            response.put("status", detectionResult.getStatus());

            log.info("File uploaded successfully from {}: {}", cameraId, fileName);

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Error uploading file from camera {}", cameraId, e);
            response.put("error", "Failed to upload file: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    @GetMapping("/results")
    public ResponseEntity<List<DetectionResult>> getRecentResults() {
        try {
            List<DetectionResult> results = yoloProcessingService.getRecentDetections();
            return ResponseEntity.ok(results);
        } catch (Exception e) {
            log.error("Error fetching detection results", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @GetMapping("/results/since")
    public ResponseEntity<List<DetectionResult>> getResultsSince(
            @RequestParam("minutes") int minutes) {
        try {
            LocalDateTime since = LocalDateTime.now().minusMinutes(minutes);
            List<DetectionResult> results = yoloProcessingService.getDetectionsSince(since);
            return ResponseEntity.ok(results);
        } catch (Exception e) {
            log.error("Error fetching detection results since {}", minutes, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @GetMapping("/results/{id}")
    public ResponseEntity<DetectionResult> getDetectionResult(@PathVariable Long id) {
        try {
            return yoloProcessingService.getRecentDetections().stream()
                    .filter(result -> result.getId().equals(id))
                    .findFirst()
                    .map(ResponseEntity::ok)
                    .orElse(ResponseEntity.notFound().build());
        } catch (Exception e) {
            log.error("Error fetching detection result for id: {}", id, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    private boolean isValidFileType(String fileName) {
        String extension = fileName.substring(fileName.lastIndexOf(".") + 1).toLowerCase();
        return switch (extension) {
            case "jpg", "jpeg", "png", "gif", "bmp", "mp4", "avi", "mov", "wmv", "mkv" -> true;
            default -> false;
        };
    }

    private boolean isValidCameraId(String cameraId) {
        return "camera_one".equals(cameraId) || "camera_two".equals(cameraId);
    }
}

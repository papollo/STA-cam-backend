package com.wavestone.stacamback.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class WebSocketDetectionResponse {

    private Long id;
    private String fileName;
    private String fileType; // IMAGE or VIDEO
    private String detections; // JSON string of YOLO detections
    private LocalDateTime processedAt;
    private String status; // PENDING, PROCESSING, COMPLETED, FAILED
    private String errorMessage;
    private Integer width; // Image or video width in pixels
    private Integer height; // Image or video height in pixels
    private String cameraId; // camera_one or camera_two
    private String imageBase64; // Base64 encoded image data
    private String mimeType; // e.g., "image/jpeg", "image/png"

    // Constructor from DetectionResult
    public WebSocketDetectionResponse(DetectionResult detectionResult) {
        this.id = detectionResult.getId();
        this.fileName = detectionResult.getFileName();
        this.fileType = detectionResult.getFileType();
        this.detections = detectionResult.getDetections();
        this.processedAt = detectionResult.getProcessedAt();
        this.status = detectionResult.getStatus();
        this.errorMessage = detectionResult.getErrorMessage();
        this.width = detectionResult.getWidth();
        this.height = detectionResult.getHeight();
        this.cameraId = detectionResult.getCameraId();
    }
}

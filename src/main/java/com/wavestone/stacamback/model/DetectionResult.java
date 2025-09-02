package com.wavestone.stacamback.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Entity
@Table(name = "detection_results")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DetectionResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String fileName;

    @Column(nullable = false)
    private String fileType; // IMAGE or VIDEO

    @Column(nullable = false)
    private String filePath;

    @Column(columnDefinition = "TEXT")
    private String detections; // JSON string of YOLO detections

    @Column(nullable = false)
    private LocalDateTime processedAt;

    @Column(nullable = false)
    private String status; // PENDING, PROCESSING, COMPLETED, FAILED

    private String errorMessage;

    private Integer width; // Image or video width in pixels
    private Integer height; // Image or video height in pixels

    @Column(nullable = false)
    private String cameraId; // camera_one or camera_two

    @PrePersist
    protected void onCreate() {
        processedAt = LocalDateTime.now();
        if (status == null) {
            status = "PENDING";
        }
    }
}

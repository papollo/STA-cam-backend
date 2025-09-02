package com.wavestone.stacamback.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class YoloDetection {
    private String className;
    private double confidence;
    private BoundingBox boundingBox;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BoundingBox {
        private double x;
        private double y;
        private double width;
        private double height;
    }
}

@Data
@NoArgsConstructor
@AllArgsConstructor
class YoloResponse {
    private String fileName;
    private String fileType;
    private List<YoloDetection> detections;
    private String status;
    private String errorMessage;
}

package com.wavestone.stacamback.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wavestone.stacamback.model.DetectionResult;
import com.wavestone.stacamback.model.WebSocketDetectionResponse;
import com.wavestone.stacamback.repository.DetectionResultRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jcodec.api.FrameGrab;
import org.jcodec.common.io.NIOUtils;
import org.jcodec.common.model.Picture;
import org.jcodec.scale.AWTUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.Base64;

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

    @Value("${app.websocket.image.max-width:800}")
    private int maxImageWidth;

    @Value("${app.websocket.image.max-height:600}")
    private int maxImageHeight;

    @Value("${app.websocket.image.quality:0.7}")
    private float imageQuality;

    @Value("${app.websocket.image.enabled:true}")
    private boolean imageWebSocketEnabled;

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
            try {
                // Extract video dimensions using JCodec
                String[] dimensions = getVideoDimensionsWithJCodec(filePath.toString());
                if (dimensions != null && dimensions.length == 2) {
                    width = Integer.parseInt(dimensions[0]);
                    height = Integer.parseInt(dimensions[1]);
                }
            } catch (Exception e) {
                log.warn("Could not read video dimensions for file: {}", fileName);
            }
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

                // Check if it's a video file for frame-by-frame processing
                if ("VIDEO".equals(detectionResult.getFileType())) {
                    return processVideoFrameByFrame(detectionResult);
                } else {
                    return processSingleFile(detectionResult);
                }

            } catch (Exception e) {
                detectionResult.setStatus("FAILED");
                detectionResult.setErrorMessage(e.getMessage());
                log.error("Error processing file with YOLO: {}", detectionResult.getFileName(), e);

                // Save final result
                DetectionResult finalResult = repository.save(detectionResult);
                // Broadcast final update
                broadcastDetectionUpdate(finalResult);
                return finalResult;
            }
        });
    }

    /**
     * Process a single image file (original logic)
     */
    private DetectionResult processSingleFile(DetectionResult detectionResult) {
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
    }

    private void broadcastDetectionUpdate(DetectionResult result) {
        try {
            WebSocketDetectionResponse response = new WebSocketDetectionResponse(result);
            
            // Add image data if it's an image file and processing is completed
            if ("IMAGE".equals(result.getFileType()) && "COMPLETED".equals(result.getStatus())) {
                try {
                    String imageBase64 = convertImageToBase64(result.getFilePath());
                    String mimeType = getMimeTypeFromFileName(result.getFileName());
                    
                    response.setImageBase64(imageBase64);
                    response.setMimeType(mimeType);
                } catch (Exception e) {
                    log.warn("Could not convert image to Base64 for file: {}", result.getFileName(), e);
                }
            }
            
            messagingTemplate.convertAndSend("/topic/detections", response);
        } catch (Exception e) {
            log.error("Failed to broadcast detection update", e);
        }
    }

    /**
     * Convert image file to compressed Base64 string with resizing and quality control
     */
    private String convertImageToBase64(String filePath) throws IOException {
        if (!imageWebSocketEnabled) {
            return null; // Skip image processing if disabled
        }

        try {
            // Read the original image
            BufferedImage originalImage = ImageIO.read(new File(filePath));
            if (originalImage == null) {
                throw new IOException("Could not read image file: " + filePath);
            }

            // Resize image if it's too large
            BufferedImage resizedImage = resizeImageIfNeeded(originalImage);

            // Compress the image to reduce size
            byte[] compressedImageBytes = compressImage(resizedImage);

            // Convert to Base64
            String base64 = Base64.getEncoder().encodeToString(compressedImageBytes);

            log.debug("Image conversion: Original size ~{}KB, Compressed size ~{}KB, Compression ratio: {:.2f}%",
                    Files.size(Paths.get(filePath)) / 1024,
                    compressedImageBytes.length / 1024,
                    (double) compressedImageBytes.length / Files.size(Paths.get(filePath)) * 100);

            return base64;
        } catch (Exception e) {
            log.error("Failed to convert and compress image: {}", filePath, e);
            throw new IOException("Image conversion failed: " + e.getMessage(), e);
        }
    }

    /**
     * Resize image if it exceeds maximum dimensions
     */
    private BufferedImage resizeImageIfNeeded(BufferedImage originalImage) {
        int originalWidth = originalImage.getWidth();
        int originalHeight = originalImage.getHeight();

        // Check if resizing is needed
        if (originalWidth <= maxImageWidth && originalHeight <= maxImageHeight) {
            return originalImage; // No resizing needed
        }

        // Calculate new dimensions while maintaining aspect ratio
        double widthRatio = (double) maxImageWidth / originalWidth;
        double heightRatio = (double) maxImageHeight / originalHeight;
        double ratio = Math.min(widthRatio, heightRatio);

        int newWidth = (int) (originalWidth * ratio);
        int newHeight = (int) (originalHeight * ratio);

        // Create resized image with high quality
        BufferedImage resizedImage = new BufferedImage(newWidth, newHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2d = resizedImage.createGraphics();

        // Set high-quality rendering hints
        g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        g2d.drawImage(originalImage, 0, 0, newWidth, newHeight, null);
        g2d.dispose();

        log.debug("Image resized from {}x{} to {}x{} (ratio: {:.2f})",
                originalWidth, originalHeight, newWidth, newHeight, ratio);

        return resizedImage;
    }

    /**
     * Compress image with specified quality
     */
    private byte[] compressImage(BufferedImage image) throws IOException {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

        // Get JPEG writer
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpg");
        if (!writers.hasNext()) {
            throw new IOException("No JPEG writer available");
        }

        ImageWriter writer = writers.next();
        ImageWriteParam param = writer.getDefaultWriteParam();

        // Set compression quality
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionQuality(imageQuality);

        // Write compressed image
        try (ImageOutputStream imageOutputStream = ImageIO.createImageOutputStream(outputStream)) {
            writer.setOutput(imageOutputStream);
            writer.write(null, new javax.imageio.IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }

        return outputStream.toByteArray();
    }

    /**
     * Get MIME type from file extension
     */
    private String getMimeTypeFromFileName(String fileName) {
        String extension = fileName.substring(fileName.lastIndexOf(".") + 1).toLowerCase();
        switch (extension) {
            case "jpg":
            case "jpeg":
                return "image/jpeg";
            case "png":
                return "image/png";
            case "gif":
                return "image/gif";
            case "bmp":
                return "image/bmp";
            default:
                return "image/jpeg"; // Default fallback
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

    /**
     * Get video dimensions using JCodec (Pure Java solution)
     *
     * @param filePath Path to the video file
     * @return Array with width and height as strings, or null if extraction fails
     */
    private String[] getVideoDimensionsWithJCodec(String filePath) {
        try {
            // Use JCodec to read video metadata
            File videoFile = new File(filePath);
            FrameGrab grab = FrameGrab.createFrameGrab(NIOUtils.readableChannel(videoFile));

            // Get video track metadata
            org.jcodec.common.DemuxerTrackMeta trackMeta = grab.getVideoTrack().getMeta();

            // Get video dimensions from track metadata
            if (trackMeta != null) {
                // JCodec stores dimensions in the track metadata differently
                org.jcodec.common.model.Size size = trackMeta.getVideoCodecMeta().getSize();

                if (size != null) {
                    String width = String.valueOf(size.getWidth());
                    String height = String.valueOf(size.getHeight());
                    log.info("JCodec extracted video dimensions: {}x{}", width, height);
                    return new String[]{width, height};
                }
            }
        } catch (Exception e) {
            log.error("Failed to extract video dimensions using JCodec for file: {}", filePath, e);
        }
        return null;
    }

    /**
     * Extract frames from video at 1-second intervals using JCodec with improved efficiency
     */
    private List<String> extractFramesFromVideo(String videoPath) throws Exception {
        List<String> frameFiles = new ArrayList<>();

        try {
            File videoFile = new File(videoPath);
            FrameGrab grab = FrameGrab.createFrameGrab(NIOUtils.readableChannel(videoFile));

            // Create frames directory
            Path framesDir = Paths.get(uploadDir, "frames");
            if (!Files.exists(framesDir)) {
                Files.createDirectories(framesDir);
            }

            // Get video metadata for more accurate frame extraction
            double fps = 25.0; // Default fallback, try to get actual FPS if possible
            int frameNumber = 0;
            int targetFrameInterval = (int) Math.round(fps / 2.0); // Extract every N frames for 5 fps (0.2 second intervals)

            log.info("Starting frame extraction from video: {} (estimated fps: {}, extracting at 5 fps)", videoPath, fps);

            Picture picture;
            while ((picture = grab.getNativeFrame()) != null && frameFiles.size() < 1500) { // Limit to 5 minutes max at 5 fps
                frameNumber++;

                // Extract frame at 0.2-second intervals (5 fps)
                if (frameNumber % targetFrameInterval == 0) {
                    double secondsExtracted = frameNumber / fps;

                    try {
                        // Convert Picture to BufferedImage more efficiently
                        BufferedImage bufferedImage = AWTUtil.toBufferedImage(picture);

                        // Create unique frame filename
                        String frameFileName = String.format("frame_%d_%.1fs_%d.jpg",
                                System.currentTimeMillis(), secondsExtracted, frameNumber);
                        Path framePath = framesDir.resolve(frameFileName);

                        // Write frame with good quality
                        ImageIO.write(bufferedImage, "jpg", framePath.toFile());
                        frameFiles.add(framePath.toString());

                        log.debug("Extracted frame at {:.1f}s (frame #{}): {}",
                                secondsExtracted, frameNumber, frameFileName);

                    } catch (Exception e) {
                        log.warn("Failed to extract frame at {:.1f}s: {}", frameNumber / fps, e.getMessage());
                        // Continue with next frame
                    }
                }
            }

            log.info("Successfully extracted {} frames from video: {}", frameFiles.size(), videoPath);

        } catch (Exception e) {
            log.error("Error extracting frames from video: {}", videoPath, e);
            throw new Exception("Frame extraction failed: " + e.getMessage(), e);
        }

        return frameFiles;
    }

    /**
     * Enhanced frame processing with better error handling and streaming
     */
    private DetectionResult processVideoFrameByFrame(DetectionResult detectionResult) {
        try {
            log.info("Starting frame-by-frame processing for video: {}", detectionResult.getFileName());

            // Extract frames at 1-second intervals
            List<String> frameFiles = extractFramesFromVideo(detectionResult.getFilePath());

            if (frameFiles.isEmpty()) {
                detectionResult.setStatus("FAILED");
                detectionResult.setErrorMessage("Could not extract any frames from video");
                repository.save(detectionResult);
                broadcastDetectionUpdate(detectionResult);
                return detectionResult;
            }

            log.info("Extracted {} frames from video: {}, starting YOLO processing",
                    frameFiles.size(), detectionResult.getFileName());

            // Broadcast processing start
            broadcastVideoProcessingStart(detectionResult, frameFiles.size());

            // Process each frame and send real-time updates
            int successfulFrames = 0;
            for (int i = 0; i < frameFiles.size(); i++) {
                String frameFile = frameFiles.get(i);
                int frameSecond = i + 1;

                try {
                    // Process this frame with YOLO
                    long startTime = System.currentTimeMillis();
                    String frameDetections = processFrameWithYolo(frameFile);
                    long processingTime = System.currentTimeMillis() - startTime;

                    // Broadcast frame result immediately with image data
                    broadcastFrameUpdate(detectionResult, frameSecond, frameDetections, processingTime, frameFile);

                    successfulFrames++;
                    log.info("Processed frame at {}s for video: {} ({}ms)",
                            frameSecond, detectionResult.getFileName(), processingTime);

                } catch (Exception e) {
                    log.error("Error processing frame at {}s for video: {}",
                            frameSecond, detectionResult.getFileName(), e);

                    // Broadcast error for this frame
                    broadcastFrameError(detectionResult, frameSecond, e.getMessage());
                }

                // Small delay to prevent overwhelming the system and WebSocket clients
                Thread.sleep(100);
            }

            // Clean up temporary frame files
            cleanupFrameFiles(frameFiles);

            // Mark video processing as completed
            detectionResult.setStatus("COMPLETED");
            detectionResult.setDetections(String.format(
                    "{\"message\":\"Video processed frame by frame\",\"totalFrames\":%d,\"successfulFrames\":%d}",
                    frameFiles.size(), successfulFrames));

            // Broadcast completion
            broadcastVideoProcessingComplete(detectionResult, frameFiles.size(), successfulFrames);

        } catch (Exception e) {
            detectionResult.setStatus("FAILED");
            detectionResult.setErrorMessage("Error in frame-by-frame processing: " + e.getMessage());
            log.error("Error in frame-by-frame processing for video: {}", detectionResult.getFileName(), e);

            // Broadcast failure
            broadcastVideoProcessingFailed(detectionResult, e.getMessage());
        }

        // Save final result
        DetectionResult finalResult = repository.save(detectionResult);
        return finalResult;
    }

    /**
     * Enhanced frame processing with YOLO - returns structured result
     */
    private String processFrameWithYolo(String framePath) throws Exception {
        String[] pythonCommands = {"python", "py", "python3", "python.exe"};

        for (String pythonCmd : pythonCommands) {
            try {
                ProcessBuilder processBuilder = new ProcessBuilder(
                        pythonCmd, pythonScriptPath, framePath
                );
                processBuilder.redirectErrorStream(true);

                long startTime = System.currentTimeMillis();
                Process process = processBuilder.start();

                StringBuilder output = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream()))) {

                    String line;
                    while ((line = reader.readLine()) != null) {
                        output.append(line).append("\n");
                    }
                }

                int exitCode = process.waitFor();
                long processingTime = System.currentTimeMillis() - startTime;

                if (exitCode == 0) {
                    String rawOutput = output.toString().trim();
                    String jsonOutput = extractJsonFromOutput(rawOutput);

                    if (jsonOutput != null && jsonOutput.startsWith("{") && jsonOutput.endsWith("}")) {
                        log.debug("YOLO processing completed for frame {} in {}ms", framePath, processingTime);
                        return jsonOutput;
                    } else {
                        throw new Exception("Invalid JSON output from YOLO: " + rawOutput);
                    }
                } else {
                    throw new Exception("YOLO processing failed with exit code " + exitCode + ": " + output.toString());
                }

            } catch (Exception e) {
                log.debug("Python command '{}' failed: {}", pythonCmd, e.getMessage());
                continue;
            }
        }

        throw new Exception("Failed to process frame with YOLO - no Python command available");
    }

    // ===================== WebSocket Broadcasting Methods =====================

    /**
     * Broadcast video processing start
     */
    private void broadcastVideoProcessingStart(DetectionResult detectionResult, int totalFrames) {
        try {
            Map<String, Object> message = new HashMap<>();
            message.put("type", "VIDEO_PROCESSING_START");
            message.put("videoId", detectionResult.getId());
            message.put("fileName", detectionResult.getFileName());
            message.put("cameraId", detectionResult.getCameraId());
            message.put("totalFrames", totalFrames);
            message.put("width", detectionResult.getWidth());
            message.put("height", detectionResult.getHeight());
            message.put("timestamp", LocalDateTime.now());

            messagingTemplate.convertAndSend("/topic/detections", message);
            log.debug("Broadcasted video processing start for: {}", detectionResult.getFileName());
        } catch (Exception e) {
            log.error("Failed to broadcast video processing start", e);
        }
    }

    /**
     * Broadcast frame update with detection results
     */
    private void broadcastFrameUpdate(DetectionResult detectionResult, int frameSecond, String detections, long processingTime) {
        try {
            Map<String, Object> message = new HashMap<>();
            message.put("type", "FRAME_DETECTION");
            message.put("videoId", detectionResult.getId());
            message.put("fileName", detectionResult.getFileName());
            message.put("cameraId", detectionResult.getCameraId());
            message.put("frameSecond", frameSecond);
            message.put("detections", detections);
            message.put("processingTime", processingTime);
            message.put("width", detectionResult.getWidth());
            message.put("height", detectionResult.getHeight());
            message.put("timestamp", LocalDateTime.now());

            messagingTemplate.convertAndSend("/topic/detections", message);
            log.debug("Broadcasted frame detection for video {} at {}s", detectionResult.getFileName(), frameSecond);
        } catch (Exception e) {
            log.error("Failed to broadcast frame detection", e);
        }
    }

    /**
     * Enhanced broadcast frame update with image data
     */
    private void broadcastFrameUpdate(DetectionResult detectionResult, int frameSecond, String detections, long processingTime, String framePath) {
        try {
            Map<String, Object> message = new HashMap<>();
            message.put("type", "FRAME_DETECTION");
            message.put("videoId", detectionResult.getId());
            message.put("fileName", detectionResult.getFileName());
            message.put("cameraId", detectionResult.getCameraId());
            message.put("frameSecond", frameSecond);
            message.put("detections", detections);
            message.put("processingTime", processingTime);
            message.put("width", detectionResult.getWidth());
            message.put("height", detectionResult.getHeight());
            message.put("timestamp", LocalDateTime.now());

            // Add frame image data
            try {
                String frameBase64 = convertImageToBase64(framePath);
                message.put("imageBase64", frameBase64);
                message.put("mimeType", "image/jpeg");
            } catch (Exception e) {
                log.warn("Could not convert frame to Base64: {}", framePath, e);
            }

            messagingTemplate.convertAndSend("/topic/detections", message);
            log.debug("Broadcasted frame detection with image for video {} at {}s", detectionResult.getFileName(), frameSecond);
        } catch (Exception e) {
            log.error("Failed to broadcast frame detection", e);
        }
    }

    /**
     * Broadcast frame processing error
     */
    private void broadcastFrameError(DetectionResult detectionResult, int frameSecond, String errorMessage) {
        try {
            Map<String, Object> message = new HashMap<>();
            message.put("type", "FRAME_ERROR");
            message.put("videoId", detectionResult.getId());
            message.put("fileName", detectionResult.getFileName());
            message.put("cameraId", detectionResult.getCameraId());
            message.put("frameSecond", frameSecond);
            message.put("error", errorMessage);
            message.put("width", detectionResult.getWidth());
            message.put("height", detectionResult.getHeight());
            message.put("timestamp", LocalDateTime.now());

            messagingTemplate.convertAndSend("/topic/detections", message);
            log.debug("Broadcasted frame error for video {} at {}s", detectionResult.getFileName(), frameSecond);
        } catch (Exception e) {
            log.error("Failed to broadcast frame error", e);
        }
    }

    /**
     * Broadcast video processing completion
     */
    private void broadcastVideoProcessingComplete(DetectionResult detectionResult, int totalFrames, int successfulFrames) {
        try {
            Map<String, Object> message = new HashMap<>();
            message.put("type", "VIDEO_PROCESSING_COMPLETE");
            message.put("videoId", detectionResult.getId());
            message.put("fileName", detectionResult.getFileName());
            message.put("cameraId", detectionResult.getCameraId());
            message.put("totalFrames", totalFrames);
            message.put("successfulFrames", successfulFrames);
            message.put("width", detectionResult.getWidth());
            message.put("height", detectionResult.getHeight());
            message.put("timestamp", LocalDateTime.now());

            messagingTemplate.convertAndSend("/topic/detections", message);
            log.info("Broadcasted video processing completion for: {}", detectionResult.getFileName());
        } catch (Exception e) {
            log.error("Failed to broadcast video processing completion", e);
        }
    }

    /**
     * Broadcast video processing failure
     */
    private void broadcastVideoProcessingFailed(DetectionResult detectionResult, String errorMessage) {
        try {
            Map<String, Object> message = new HashMap<>();
            message.put("type", "VIDEO_PROCESSING_FAILED");
            message.put("videoId", detectionResult.getId());
            message.put("fileName", detectionResult.getFileName());
            message.put("cameraId", detectionResult.getCameraId());
            message.put("error", errorMessage);
            message.put("width", detectionResult.getWidth());
            message.put("height", detectionResult.getHeight());
            message.put("timestamp", LocalDateTime.now());

            messagingTemplate.convertAndSend("/topic/detections", message);
            log.error("Broadcasted video processing failure for: {}", detectionResult.getFileName());
        } catch (Exception e) {
            log.error("Failed to broadcast video processing failure", e);
        }
    }

    /**
     * Clean up temporary frame files
     */
    private void cleanupFrameFiles(List<String> frameFiles) {
        for (String frameFile : frameFiles) {
            try {
                Files.deleteIfExists(Paths.get(frameFile));
            } catch (Exception e) {
                log.warn("Could not delete temporary frame file: {}", frameFile);
            }
        }
        log.info("Cleaned up {} temporary frame files", frameFiles.size());
    }
}

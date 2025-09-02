#!/usr/bin/env python3
"""
YOLO v10 processor for detecting objects in images and videos.
This script processes uploaded files and returns detection results as JSON.
"""

import sys
import json
import cv2
import numpy as np
from pathlib import Path
import traceback

try:
    from ultralytics import YOLO
except ImportError:
    print(json.dumps({
        "error": "YOLOv10 not installed. Please install with: pip install ultralytics",
        "status": "failed"
    }))
    sys.exit(1)

def process_image(model, image_path):
    """Process a single image and return detections."""
    try:
        # Load and process image
        image = cv2.imread(str(image_path))
        if image is None:
            raise ValueError(f"Could not load image: {image_path}")

        # Run YOLO detection
        results = model(image)

        detections = []
        for result in results:
            boxes = result.boxes
            if boxes is not None:
                for box in boxes:
                    # Extract detection information
                    x1, y1, x2, y2 = box.xyxy[0].cpu().numpy()
                    confidence = float(box.conf[0].cpu().numpy())
                    class_id = int(box.cls[0].cpu().numpy())
                    class_name = model.names[class_id]

                    detection = {
                        "className": class_name,
                        "confidence": confidence,
                        "boundingBox": {
                            "x": float(x1),
                            "y": float(y1),
                            "width": float(x2 - x1),
                            "height": float(y2 - y1)
                        }
                    }
                    detections.append(detection)

        return detections

    except Exception as e:
        raise Exception(f"Error processing image: {str(e)}")

def process_video(model, video_path):
    """Process a video and return detections from key frames."""
    try:
        cap = cv2.VideoCapture(str(video_path))
        if not cap.isOpened():
            raise ValueError(f"Could not open video: {video_path}")

        total_frames = int(cap.get(cv2.CAP_PROP_FRAME_COUNT))
        fps = cap.get(cv2.CAP_PROP_FPS)

        # Sample frames (every 30 frames or 1 second intervals)
        frame_interval = max(1, int(fps)) if fps > 0 else 30

        all_detections = []
        frame_count = 0

        while True:
            ret, frame = cap.read()
            if not ret:
                break

            if frame_count % frame_interval == 0:
                # Process this frame
                results = model(frame)

                frame_detections = []
                for result in results:
                    boxes = result.boxes
                    if boxes is not None:
                        for box in boxes:
                            x1, y1, x2, y2 = box.xyxy[0].cpu().numpy()
                            confidence = float(box.conf[0].cpu().numpy())
                            class_id = int(box.cls[0].cpu().numpy())
                            class_name = model.names[class_id]

                            detection = {
                                "className": class_name,
                                "confidence": confidence,
                                "boundingBox": {
                                    "x": float(x1),
                                    "y": float(y1),
                                    "width": float(x2 - x1),
                                    "height": float(y2 - y1)
                                },
                                "frame": frame_count,
                                "timestamp": frame_count / fps if fps > 0 else frame_count
                            }
                            frame_detections.append(detection)

                if frame_detections:
                    all_detections.extend(frame_detections)

            frame_count += 1

        cap.release()
        return all_detections

    except Exception as e:
        raise Exception(f"Error processing video: {str(e)}")

def main():
    if len(sys.argv) != 2:
        print(json.dumps({
            "error": "Usage: python yolo_processor.py <file_path>",
            "status": "failed"
        }))
        sys.exit(1)

    file_path = Path(sys.argv[1])

    if not file_path.exists():
        print(json.dumps({
            "error": f"File not found: {file_path}",
            "status": "failed"
        }))
        sys.exit(1)

    try:
        # Load YOLO model with verbose=False to suppress output
        model = YOLO('yolov8n.pt')  # Use YOLOv8 nano which is more stable
        model.verbose = False  # Suppress verbose output

        # Determine file type and process accordingly
        file_extension = file_path.suffix.lower()

        if file_extension in ['.jpg', '.jpeg', '.png', '.gif', '.bmp']:
            detections = process_image(model, file_path)
            file_type = "IMAGE"
        elif file_extension in ['.mp4', '.avi', '.mov', '.wmv', '.mkv']:
            detections = process_video(model, file_path)
            file_type = "VIDEO"
        else:
            raise ValueError(f"Unsupported file type: {file_extension}")

        # Output results as JSON
        result = {
            "fileName": file_path.name,
            "fileType": file_type,
            "detections": detections,
            "status": "completed",
            "totalDetections": len(detections)
        }

        print(json.dumps(result, indent=2))

    except Exception as e:
        error_result = {
            "fileName": file_path.name,
            "fileType": "UNKNOWN",
            "detections": [],
            "status": "failed",
            "errorMessage": str(e),
            "traceback": traceback.format_exc()
        }
        print(json.dumps(error_result, indent=2))
        sys.exit(1)

if __name__ == "__main__":
    main()

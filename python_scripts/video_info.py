#!/usr/bin/env python3
"""
Video information extractor using OpenCV
Usage: python video_info.py <video_file_path>
Returns: width,height (comma-separated)
"""
import sys
import cv2

def get_video_dimensions(video_path):
    try:
        # Open video file
        cap = cv2.VideoCapture(video_path)

        if not cap.isOpened():
            print("Error: Could not open video file", file=sys.stderr)
            return None

        # Get video properties
        width = int(cap.get(cv2.CAP_PROP_FRAME_WIDTH))
        height = int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT))

        # Release the video capture object
        cap.release()

        return width, height

    except Exception as e:
        print(f"Error reading video: {e}", file=sys.stderr)
        return None

if __name__ == "__main__":
    if len(sys.argv) != 2:
        print("Usage: python video_info.py <video_file_path>", file=sys.stderr)
        sys.exit(1)

    video_path = sys.argv[1]
    dimensions = get_video_dimensions(video_path)

    if dimensions:
        width, height = dimensions
        print(f"{width},{height}")
        sys.exit(0)
    else:
        sys.exit(1)

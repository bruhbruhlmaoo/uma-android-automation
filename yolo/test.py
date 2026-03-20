"""
YOLO model testing and visualization script.

This script provides an interactive visualization tool for testing trained
YOLO models on images. Features include zooming, panning, and hover
highlighting of detected objects.
"""

import logging
import os
import random
from pathlib import Path
from typing import Any, Dict, List, Tuple

import cv2
import numpy as np
from ultralytics import YOLO

from config import RUN_NAME, STAT_REGIONS

# --- CONFIGURATION ---
IMAGE_NAME = ""
MODEL_FORMAT = "pt"  # Options: "pt", "tflite"
PREDICT_CONFIDENCE = 0.5
WINDOW_WIDTH = 720
WINDOW_HEIGHT = 1280
ZOOM_SCALE = 1.0
ZOOM_CENTER_REL = [0.5, 0.5]
MOUSE_IMG_POS = (0, 0)
IS_DRAGGING = False
LAST_MOUSE_POS = (0, 0)
FOLDER_NAME = "images_original"


def mouse_callback(event: int, x: int, y: int, flags: int, param: Any) -> None:
    """
    Handles mouse events for zooming, panning, and coordinate tracking.

    Args:
        event: The type of mouse event (e.g., scroll, click, move).
        x: The x-coordinate of the mouse position.
        y: The y-coordinate of the mouse position.
        flags: Additional flags for the event (e.g., scroll direction).
        param: Optional user data passed to the callback.
    """
    global ZOOM_SCALE, ZOOM_CENTER_REL, MOUSE_IMG_POS, IS_DRAGGING, LAST_MOUSE_POS

    # Ignore events outside the window bounds.
    if x < 0 or y < 0 or x > WINDOW_WIDTH or y > WINDOW_HEIGHT:
        return

    MOUSE_IMG_POS = (x, y)

    # Handle mouse wheel for zooming.
    if event == cv2.EVENT_MOUSEWHEEL:
        rel_x, rel_y = x / WINDOW_WIDTH, y / WINDOW_HEIGHT

        if flags > 0:
            ZOOM_SCALE *= 1.2
        else:
            ZOOM_SCALE /= 1.2
        ZOOM_SCALE = max(1.0, min(ZOOM_SCALE, 15.0))

        if ZOOM_SCALE > 1.0:
            ZOOM_CENTER_REL[0] = (ZOOM_CENTER_REL[0] + rel_x) / 2
            ZOOM_CENTER_REL[1] = (ZOOM_CENTER_REL[1] + rel_y) / 2
        else:
            ZOOM_CENTER_REL = [0.5, 0.5]

    # Handle mouse drag for panning.
    if event == cv2.EVENT_LBUTTONDOWN:
        IS_DRAGGING = True
        LAST_MOUSE_POS = (x, y)
    elif event == cv2.EVENT_LBUTTONUP:
        IS_DRAGGING = False
    elif event == cv2.EVENT_MOUSEMOVE and IS_DRAGGING:
        dx = (LAST_MOUSE_POS[0] - x) / WINDOW_WIDTH / ZOOM_SCALE
        dy = (LAST_MOUSE_POS[1] - y) / WINDOW_HEIGHT / ZOOM_SCALE
        ZOOM_CENTER_REL[0] = np.clip(ZOOM_CENTER_REL[0] + dx, 0, 1)
        ZOOM_CENTER_REL[1] = np.clip(ZOOM_CENTER_REL[1] + dy, 0, 1)
        LAST_MOUSE_POS = (x, y)


def draw_element(frame: np.ndarray, overlay: np.ndarray, item: Dict[str, Any], is_hovered: bool) -> None:
    """
    Draws a single detection element including box, arrow, and label.

    Args:
        frame: The main image frame to draw on.
        overlay: A copy of the frame for drawing semi-transparent fills.
        item: Detection metadata containing box coords, label rect, text, and color.
        is_hovered: Whether the element is currently being hovered over.
    """
    lx1, ly1, lx2, ly2 = item["label_rect"]
    bx1, by1, bx2, by2 = item["box"]
    draw_color = (0, 255, 0) if is_hovered else item["color"]
    thickness = 4 if is_hovered else 2

    # Draw box fill on overlay for transparency effect.
    cv2.rectangle(overlay, (bx1, by1), (bx2, by2), draw_color, -1)

    # Draw box outline.
    cv2.rectangle(frame, (bx1, by1), (bx2, by2), draw_color, thickness)

    # Draw arrow/leader line from label to box.
    label_midpoint = (lx1 + (lx2 - lx1) // 2, ly2)
    box_midpoint = (bx1 + (bx2 - bx1) // 2, by1)

    # Shadow line for visibility.
    cv2.arrowedLine(
        frame, label_midpoint, box_midpoint, (0, 0, 0), thickness + 2, tipLength=0.05
    )

    # Main arrow line.
    line_color = (255, 255, 255) if not is_hovered else (0, 255, 0)
    cv2.arrowedLine(frame, label_midpoint, box_midpoint, line_color, 1, tipLength=0.05)

    # Draw label badge.
    cv2.rectangle(frame, (lx1, ly1), (lx2, ly2), (255, 255, 255), -1)
    cv2.rectangle(frame, (lx1, ly1), (lx2, ly2), draw_color, thickness)
    cv2.putText(
        frame, item["text"], (lx1 + 2, ly2 - 10),
        cv2.FONT_HERSHEY_SIMPLEX, 0.8, (0, 0, 0), 2
    )


def find_non_overlapping_label_position(initial_x: int,
    initial_y: int,
    text_width: int,
    text_height: int,
    occupied_areas: List[Tuple[int, int, int, int]],
    max_attempts: int = 15
) -> Tuple[int, int, int, int]:
    """
    Finds a non-overlapping position for a label.

    Args:
        initial_x: Starting x-coordinate for the label.
        initial_y: Starting y-coordinate (bottom) for the label.
        text_width: Width of the label text.
        text_height: Height of the label text.
        occupied_areas: List of already occupied label rectangles.
        max_attempts: Maximum number of repositioning attempts.

    Returns:
        A tuple of (x1, y1, x2, y2) for the final label position.
    """
    lx1 = initial_x
    ly2 = initial_y
    ly1 = ly2 - text_height - 15
    padding = 10

    for _ in range(max_attempts):
        current_rect = (lx1 - padding, ly1 - padding, lx1 + text_width + padding, ly2 + padding)

        # Check for overlap with existing labels.
        has_overlap = any(
            not (current_rect[2] < ox1 or current_rect[0] > ox2 or
                 current_rect[3] < oy1 or current_rect[1] > oy2)
            for (ox1, oy1, ox2, oy2) in occupied_areas
        )

        if not has_overlap:
            break

        # Shift label up and slightly right to avoid overlap.
        ly1 -= (text_height + 60)
        ly2 -= (text_height + 60)
        lx1 += 10

    return (lx1, ly1, lx1 + text_width, ly2)


def load_latest_model() -> YOLO | None:
    """
    Loads the YOLO model from the most recent training run based on CONFIG.

    Returns:
        The loaded YOLO model, or None if no runs are found.
    """
    run_dir = Path("runs/detect")
    runs = [d for d in run_dir.iterdir() if d.is_dir() and (d.name == RUN_NAME or d.name.startswith(f"{RUN_NAME}_"))]

    if not runs:
        logging.error("No training runs found.")
        return None

    latest_run = max(runs, key=lambda d: d.stat().st_mtime)
    weights_dir = latest_run / "weights"

    # Determine weight file path based on format.
    if MODEL_FORMAT == "pt":
        weights_path = weights_dir / "best.pt"
    elif MODEL_FORMAT == "onnx":
        weights_path = weights_dir / "best.onnx"
    elif MODEL_FORMAT == "tflite":
        # Check for float16 first, then float32 as fallback.
        tflite_dir = weights_dir / "best_saved_model"
        weights_path = tflite_dir / "best_float16.tflite"
        if not weights_path.exists():
            weights_path = tflite_dir / "best_float32.tflite"
    else:
        logging.error(f"Unsupported model format: {MODEL_FORMAT}")
        return None

    if not weights_path.exists():
        logging.error(f"Model file not found at: {weights_path}")
        return None

    logging.info(f"Loading model ({MODEL_FORMAT}) from: {weights_path}")

    # Ultralytics YOLO class handles .pt, .onnx, and .tflite directly.
    return YOLO(str(weights_path))


def select_test_images(img_dir: Path) -> Tuple[List[Path], int]:
    """
    Retrieves all test images and determines the starting index.

    Args:
        img_dir: Path to the images directory.

    Returns:
        A tuple containing the list of image paths and the starting index.
    """
    images = sorted([img_dir / f for f in os.listdir(img_dir) if f.lower().endswith((".png", ".jpg"))])

    if not images:
        logging.error(f"No images found in directory: {img_dir}")
        return [], 0

    start_index = 0
    if IMAGE_NAME:
        image_path = img_dir / IMAGE_NAME
        if image_path in images:
            start_index = images.index(image_path)
    else:
        # Pick a random starting image if no specific image is requested.
        start_index = random.randint(0, len(images) - 1)

    return images, start_index


def translate_box_to_original(box: Tuple[int, int, int, int], crop_offset: Tuple[int, int]) -> Tuple[int, int, int, int]:
    """
    Translates a bounding box from crop coordinates back to original image coordinates.

    Args:
        box: Bounding box in (x1, y1, x2, y2) format relative to the crop.
        crop_offset: The (x, y) offset of the crop relative to the original image.

    Returns:
        A tuple of (x1, y1, x2, y2) relative to the original image.
    """
    ox, oy = crop_offset
    bx1, by1, bx2, by2 = box
    return (bx1 + ox, by1 + oy, bx2 + ox, by2 + oy)


def build_detection_metadata(raw_detections: List[Dict[str, Any]], model: YOLO, img_height: int) -> List[Dict[str, Any]]:
    """
    Builds metadata for all detections including positioned labels.

    Args:
        raw_detections: List of detections with 'box', 'cls', and 'conf'.
        model: The YOLO model (used for class name lookup).
        img_height: Height of the original image for label positioning.

    Returns:
        List of detection metadata dictionaries.
    """
    labels_metadata = []
    occupied_label_areas = []

    for det in raw_detections:
        bx1, by1, bx2, by2 = det["box"]
        cls = det["cls"]
        conf = det["conf"]
        label_text = f"{model.names[cls]} {conf:.2f}"

        (text_w, text_h), _ = cv2.getTextSize(
            label_text, cv2.FONT_HERSHEY_SIMPLEX, 0.8, 2
        )

        # Position label above box, higher up if box is in lower portion of image.
        initial_y = by1 - (150 if by1 > (img_height * 0.7) else 60)
        label_rect = find_non_overlapping_label_position(
            bx1, initial_y, text_w, text_h, occupied_label_areas
        )

        occupied_label_areas.append(label_rect)

        # Use different colors for different detection types.
        color = (255, 150, 50) if model.names[cls] != "+" else (200, 50, 200)

        labels_metadata.append({
            "box": (bx1, by1, bx2, by2),
            "label_rect": label_rect,
            "text": label_text,
            "color": color
        })

    return labels_metadata


def run_interactive_viewer() -> None:
    """
    Runs the interactive image viewer with detection visualization.

    This function loads the latest trained model, runs prediction on test image
    ROIs, and displays an interactive window with zoom/pan capabilities.
    Pressing ENTER cycles to the next image in the folder.
    """
    global ZOOM_SCALE, ZOOM_CENTER_REL, MOUSE_IMG_POS

    model = load_latest_model()
    if model is None:
        return

    # Path to the folder with the images to test on.
    img_dir = Path(FOLDER_NAME)
    image_paths, current_index = select_test_images(img_dir)
    if not image_paths:
        return

    window_name = "Explorer"
    cv2.namedWindow(window_name, cv2.WINDOW_NORMAL)
    cv2.resizeWindow(window_name, WINDOW_WIDTH, WINDOW_HEIGHT)
    cv2.setMouseCallback(window_name, mouse_callback)

    while True:
        img_path = image_paths[current_index]
        logging.info(f"Testing on image: {img_path.name}")

        # Reset interactive state for the new image.
        ZOOM_SCALE = 1.0
        ZOOM_CENTER_REL = [0.5, 0.5]

        base_img = cv2.imread(str(img_path))
        if base_img is None:
            logging.error(f"Failed to load image: {img_path}")
            break

        h_orig, w_orig = base_img.shape[:2]

        # Perform inference on localized crops.
        all_detections = []
        for stat_name, regions in STAT_REGIONS.items():
            for i, (x, y, w, h) in enumerate(regions, 1):
                # Extract crop.
                crop = base_img[y:y+h, x:x+w]
                
                # Run prediction on crop.
                # Use imgsz=128 as the model was trained on small crops.
                results = model.predict(source=crop, imgsz=128, conf=PREDICT_CONFIDENCE, verbose=False)[0]
                
                # Translate detections back to original image space.
                for box in results.boxes:
                    coords = map(int, box.xyxy[0])
                    translated_box = translate_box_to_original(tuple(coords), (x, y))
                    all_detections.append({
                        "box": translated_box,
                        "cls": int(box.cls[0]),
                        "conf": float(box.conf[0])
                    })

        # Build detection metadata with positioned labels.
        labels_metadata = build_detection_metadata(all_detections, model, h_orig)
        logging.info(f"Found {len(labels_metadata)} detections across all regions.")

        # Update window title with image name.
        cv2.setWindowTitle(window_name, f"Explorer: {img_path.name}")

        # Display loop for the current image.
        while True:
            if cv2.getWindowProperty(window_name, cv2.WND_PROP_VISIBLE) < 1:
                return

            # Calculate crop region based on zoom/pan state.
            crop_w = int(w_orig / ZOOM_SCALE)
            crop_h = int(h_orig / ZOOM_SCALE)
            center_x = int(w_orig * ZOOM_CENTER_REL[0])
            center_y = int(h_orig * ZOOM_CENTER_REL[1])
            x1_crop = max(0, min(center_x - crop_w // 2, w_orig - crop_w))
            y1_crop = max(0, min(center_y - crop_h // 2, h_orig - crop_h))

            # Map mouse position to image coordinates for hover effects.
            mx_img = int(x1_crop + (MOUSE_IMG_POS[0] / WINDOW_WIDTH) * crop_w)
            my_img = int(y1_crop + (MOUSE_IMG_POS[1] / WINDOW_HEIGHT) * crop_h)

            frame = base_img.copy()
            overlay = base_img.copy()

            # Pass 1: Draw non-hovered items.
            hovered_item = None
            for item in labels_metadata:
                lx1, ly1, lx2, ly2 = item["label_rect"]
                bx1, by1, bx2, by2 = item["box"]

                is_hovering_label = lx1 <= mx_img <= lx2 and ly1 <= my_img <= ly2
                is_hovering_box = bx1 <= mx_img <= bx2 and by1 <= my_img <= by2

                if is_hovering_label or is_hovering_box:
                    hovered_item = item
                    continue

                draw_element(frame, overlay, item, is_hovered=False)

            # Pass 2: Draw hovered item on top.
            if hovered_item:
                draw_element(frame, overlay, hovered_item, is_hovered=True)

            # Blend overlay for semi-transparent box fills.
            blended = cv2.addWeighted(overlay, 0.3, frame, 0.7, 0)

            # Crop and resize for display according to current zoom/pan state.
            display = cv2.resize(
                blended[y1_crop:y1_crop + crop_h, x1_crop:x1_crop + crop_w],
                (WINDOW_WIDTH, WINDOW_HEIGHT)
            )
            cv2.imshow(window_name, display)

            # Handle keyboard input.
            key = cv2.waitKey(1) & 0xFF
            if key == 27 or key == ord("q"):
                cv2.destroyAllWindows()
                return
            if key == 13:  # ENTER key.
                current_index = (current_index + 1) % len(image_paths)
                break

    cv2.destroyAllWindows()


if __name__ == "__main__":
    logging.basicConfig(format="%(asctime)s - %(levelname)s - %(message)s", level=logging.INFO)
    run_interactive_viewer()

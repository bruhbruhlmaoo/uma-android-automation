# Stat Gain Detection with YOLO

This project implements a specialized YOLO-based object detection system to identify and extract stat gains (numbers 0-9 and the '+' symbol) from localized regions of Umamusume Pretty Derby screenshots.

## Project Overview

- **Input Format**: 130x50 localized crops extracted from 1080x2340 screenshots.
- **Model Architecture**: Ultralytics YOLOv8 (specifically `yolov8n.pt` fine-tuned).
- **Core Purpose**: High-precision detection of stat increases using a specialized ROI (Region of Interest) workflow.
- **Image Size**: Optimized for small objects with `imgsz=128`.

## Specialized ROI Workflow

To improve accuracy and efficiency, the project shifted from full-frame inference to a specialized crop-based approach:

1.  **Region Definition**: 10 specific regions (5 stats x 2 regions each) are defined in `config.py` for cropping.
2.  **Dataset Preparation**: The `crop_dataset.py` script extracts these ROIs from full screenshots in `images_original/` and saves them as specialized training samples.
3.  **Specialized Training**: Training is performed on these localized crops using `imgsz=128` to focus the model's capacity on digit recognition.
4.  **Local Inference**: During testing/inference, the full image is divided into these same 10 regions. Predictions are run on each crop individually, and detected coordinates are translated back to the global image space.

## Configuration Centralization

The `config.py` file serves as the central hub for shared settings across all scripts:
- `RUN_NAME`: Unique identifier for the current training/model version.
- `IMGSZ`: Shared image size (128) for training and inference.
- `STAT_REGIONS`: The (x, y, w, h) coordinates for the 10 detection regions.

## Project Structure

- `yolo.py`: Main training and utility script.
- `test.py`: Interactive visualization tool with localized ROI inference.
- `crop_dataset.py`: Utility to process screenshots into training crops.
- `config.py`: Shared configuration and ROI definitions.
- `output_crops/`: Destination for extracted 130x50 training samples using the `crop_dataset.py` script.
- `dataset.yaml`: Dataset configuration for YOLO.
- `images_and_labels.zip`: Archive containing all training images and their corresponding annotations.
- `runs.zip`: Archive containing the training run(s) that produced the latest version of `best.pt`.

## Usage

### 1. Generating Training Data
Process raw screenshots into the specialized crops required for training:
```powershell
python crop_dataset.py
```

### 2. Training
Run the training pipeline (automatically uses settings from `config.py`):
```powershell
python yolo.py
```

### 3. Testing & Visualization
The interactive visualization tool (`test.py`) allows you to verify performance on full-size images:
- **Localized Inference**: Automatically extracts ROIs, runs the model at `imgsz=128`, and re-maps detections to the full screenshot.
- **Interactive UI**: Zoom with the mouse wheel and pan by dragging.
- **Auto-loading**: Automatically loads the latest `best.pt` from the `runs/` directory.
- **Smart Labels**: Highlights detections on hover and positions labels to avoid overlap.
- **Random Testing**: Picks a random image from `/images_original` if no specific file is specified.

**Run the Tester**:
```powershell
python test.py
```

**Controls**:
- `Mouse Wheel`: Zoom In/Out
- `Left Click + Drag`: Pan
- `Hover`: Highlight specific detection
- `ESC / Q`: Close viewer
- `ENTER`: Next image

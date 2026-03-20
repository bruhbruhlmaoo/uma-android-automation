"""
YOLO training script for Umamusume stat gain detection.

This script handles dataset preparation, model weight management,
and training configuration for fine-tuning YOLOv8 models.
"""

import os
import shutil
import random
import sys
import logging
from pathlib import Path
from typing import List
import yaml
import time
from ultralytics import YOLO

from config import RUN_NAME, IMGSZ, TOTAL_RUNS, TRAIN_RATIO


# --- CONFIGURATION ---
# Imported from config.py


def prepare_incremental_data(run_index: int, total_runs: int, train_ratio: float = 0.8):
    """
    Splits a subset of labeled images into train and validation folders.

    The subset size grows linearly with the run_index (e.g., 20%, 40%, ..., 100%).

    Args:
        run_index (int): The current run index (1-based).
        total_runs (int): Total number of training runs planned.
        train_ratio (float): The ratio of images to use for training (default 0.8).
    """
    src_images = Path("images")
    src_labels = Path("labels")

    all_image_files = [
        f for f in os.listdir(src_images)
        if f.lower().endswith((".png", ".jpg", ".jpeg"))
    ]
    labeled_images = []

    for img_name in all_image_files:
        label_name = Path(img_name).stem + ".txt"
        if (src_labels / label_name).exists():
            labeled_images.append(img_name)

    labeled_count = len(labeled_images)
    if labeled_count == 0:
        logging.error("No labeled images found in /labels. Check your export!")
        sys.exit(1)

    # Use a fixed seed for shuffling so that the subset growth is consistent.
    random.seed(42)
    random.shuffle(labeled_images)

    # Calculate the size of the total dataset to use for this run.
    subset_size = int(labeled_count * (run_index / total_runs))
    current_subset = labeled_images[:subset_size]

    logging.info(f"\n--- Run {run_index}/{total_runs} ---")
    logging.info(f"Using {len(current_subset)} images ({int(run_index/total_runs*100)}% of total dataset)")

    # Clean and recreate target directories.
    for split in ["train", "val"]:
        split_path = Path(split)
        if split_path.exists():
            shutil.rmtree(split_path)
        (split_path / "images").mkdir(parents=True)
        (split_path / "labels").mkdir(parents=True)

    split_idx = int(len(current_subset) * train_ratio)
    train_files = current_subset[:split_idx]
    val_files = current_subset[split_idx:]

    def move_to_split(files: List[str], target_subset: str):
        for f in files:
            shutil.copy(src_images / f, Path(target_subset) / "images" / f)
            label_name = Path(f).stem + ".txt"
            shutil.copy(src_labels / label_name, Path(target_subset) / "labels" / label_name)

    logging.info(f"Splitting: {len(train_files)} training, {len(val_files)} validation...")
    move_to_split(train_files, "train")
    move_to_split(val_files, "val")


def validate_and_sync_metadata(weights_path: str, yaml_path: str):
    """
    Validates that model class names match the dataset YAML configuration.

    If a mismatch is detected between the model's stored class names and the
    YAML configuration, updates the model metadata to match the YAML.

    Args:
        weights_path (str): Path to the model weights file.
        yaml_path (str): Path to the dataset.yaml configuration file.
    """
    if weights_path == "yolov8n.pt":
        return

    with open(yaml_path, "r") as f:
        data_yaml = yaml.safe_load(f)

    yaml_names = data_yaml.get("names")
    model = YOLO(weights_path)
    model_names = model.model.names

    mismatch = False
    for idx, name in yaml_names.items():
        if model_names.get(idx) != name:
            mismatch = True
            break

    if mismatch:
        logging.warning("Metadata mismatch detected! Updating model metadata...")
        model.model.names = yaml_names
    else:
        logging.info("Metadata verified: YAML and model names are in sync.")


def get_latest_weights() -> str:
    """
    Finds the best.pt from the most recently modified umamusume folder.

    Searches the runs/detect directory for folders starting with "umamusume"
    and returns the path to best.pt from the most recently modified one.

    Returns:
        Path to the latest weights file, or "yolov8n.pt" if none found.
    """
    run_dir = Path("runs/detect")
    if not run_dir.exists():
        return "yolov8n.pt"

    umamusume_runs = [
        d for d in run_dir.iterdir()
        if d.is_dir() and (d.name == RUN_NAME or d.name.startswith(f"{RUN_NAME}_"))
    ]
    if not umamusume_runs:
        return "yolov8n.pt"

    latest_run = max(umamusume_runs, key=lambda d: d.stat().st_mtime)
    weights_path = latest_run / "weights" / "best.pt"

    if weights_path.exists():
        logging.info(f"Found latest weights: {weights_path}")
        return str(weights_path)

    return "yolov8n.pt"


def main():
    """
    Main entry point for the incremental YOLO training pipeline.
    """
    for i in range(1, TOTAL_RUNS + 1):
        prepare_incremental_data(i, TOTAL_RUNS, TRAIN_RATIO)
        weights_path = get_latest_weights()
        validate_and_sync_metadata(weights_path, "dataset.yaml")

        model = YOLO(weights_path)
        model.train(
            data="dataset.yaml",
            epochs=100 // TOTAL_RUNS if i < TOTAL_RUNS else 100, # Distribute epochs or do full final run
            imgsz=IMGSZ,
            device=0,
            name=f"{RUN_NAME}_{i}",
            exist_ok=False,
            augment=True,
            degrees=0,
            scale=0.1,
            perspective=0.0001,
            workers=4,
        )

    logging.info("\n--- Training Complete ---")
    best_weights = get_latest_weights()
    logging.info(f"Best weights located at: {best_weights}")


if __name__ == "__main__":
    logging.basicConfig(format="%(asctime)s - %(levelname)s - %(message)s", level=logging.INFO)
    start_time = time.time()

    main()

    end_time = time.time()
    logging.info(f"Total training time: {round(end_time - start_time, 2)} seconds ({round((end_time - start_time) / 60, 2)} minutes)")

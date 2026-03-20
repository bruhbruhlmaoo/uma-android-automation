"""
Export script for Umamusume YOLOv8 models.

This script allows exporting trained YOLOv8 models to various formats.
"""

import logging
import sys
from pathlib import Path
from ultralytics import YOLO

from config import RUN_NAME, IMGSZ


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
    logging.info("\n--- Export Options ---")
    best_weights = get_latest_weights()
    
    if best_weights == "yolov8n.pt":
        logging.warning("--- Warning: No custom trained weights found. ---")
        confirm = input("Do you want to export the base 'yolov8n.pt' model? (y/n): ").strip().lower()
        if confirm != 'y':
            logging.info("Exiting.")
            sys.exit(0)
    
    print(f"Ready to export: {best_weights}")
    print("\nSelect export format for Android:")
    print("[1] ONNX (default)")
    print("[2] TFLite")
    print("[3] PyTorch (TorchScript)")
    print("[4] All")
    print("[5] None / Skip")
    
    choice = input("Enter your choice (1-5): ").strip()
    
    export_formats = []
    if choice == "2":
        export_formats.append("tflite")
    elif choice == "3":
        export_formats.append("torchscript")
    elif choice == "4":
        export_formats.append("onnx")
        export_formats.append("tflite")
        export_formats.append("torchscript")
    elif choice == "5":
        logging.info("Skipping export.")
        sys.exit(0)
    else:
        # Default to ONNX if 1 or invalid input.
        export_formats.append("onnx")

    print("\nSelect quantization level:")
    print("[1] FP32 (Full precision, default)")
    print("[2] FP16 (Half precision)")
    print("[3] INT8 (Integer quantization)")
    print("[4] All")
    
    quant_choice = input("Enter your choice (1-4): ").strip()
    
    # Map choices to (half, int8) flags.
    quant_options = []
    if quant_choice == "2":
        quant_options.append(("fp16", True, False))
    elif quant_choice == "3":
        quant_options.append(("int8", False, True))
    elif quant_choice == "4":
        quant_options.append(("fp32", False, False))
        quant_options.append(("fp16", True, False))
        quant_options.append(("int8", False, True))
    else:
        # Default to FP32 if 1 or invalid input.
        quant_options.append(("fp32", False, False))

    if export_formats:
        export_model = YOLO(best_weights)
        for fmt in export_formats:
            for q_name, q_half, q_int8 in quant_options:
                # INT8 is primarily for TFLite in this context.
                if fmt == "torchscript" and q_int8:
                    logging.warning("Skipping INT8 for TorchScript as it is not directly supported via export flags.")
                    continue
                    
                logging.info(f"Exporting to {fmt} with {q_name} quantization...")
                try:
                    # Note: data="dataset.yaml" is required for INT8 TFLite metadata/calibration.
                    exported_path = export_model.export(
                        format=fmt, 
                        imgsz=IMGSZ, 
                        data="dataset.yaml", 
                        half=q_half, 
                        int8=q_int8
                    )
                    
                    if exported_path:
                        # Rename the exported file to include the quantization suffix.
                        # e.g., best.tflite -> best_int8.tflite
                        path = Path(exported_path)
                        new_name = f"{path.stem}_{q_name}{path.suffix}"
                        new_path = path.with_name(new_name)
                        
                        # Handle potential existing files.
                        if new_path.exists():
                            new_path.unlink()
                        path.rename(new_path)
                        logging.info(f"--- {fmt} ({q_name}) export complete: {new_path.name} ---")
                    else:
                        logging.warning(f"Export returned no path for {fmt} ({q_name})")
                except Exception as e:
                    logging.error(f"Failed to export to {fmt} ({q_name}): {e}")


if __name__ == "__main__":
    logging.basicConfig(format="%(asctime)s - %(levelname)s - %(message)s", level=logging.INFO)
    main()

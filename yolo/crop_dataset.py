"""
Dataset cropping script for Umamusume stat gain detection.

This script extracts specific regions of interest (ROI) from training images
to create a specialized dataset for digits and stat symbols.
"""

import cv2
from pathlib import Path

from config import STAT_REGIONS



def crop_image_regions(image_path, output_dir):
    """
    Crops specific stat regions from an image and saves them to disk.

    Args:
        image_path (Path): Path to the source image file.
        output_dir (Path): Directory where cropped images will be saved.
    """
    # Load the image using OpenCV.
    img = cv2.imread(str(image_path))
    if img is None:
        print(f"Failed to load image: {image_path}")
        return

    # Extract the file name without extension.
    base_name = image_path.stem

    # Iterate through each stat and its corresponding coordinate regions.
    for stat_name, regions in STAT_REGIONS.items():
        for i, (x, y, w, h) in enumerate(regions, 1):
            # Crop the region using NumPy slicing [y:y+h, x:x+w].
            crop = img[y:y+h, x:x+w]

            # Construct the output filename using the required naming convention.
            output_filename = f"{base_name}_{stat_name}_{i}.png"
            output_path = output_dir / output_filename

            # Write the cropped image to the output directory.
            cv2.imwrite(str(output_path), crop)


def main():
    """
    Main entry point for the dataset cropping process.

    This function processes a specific subset of images (first 30 and last 30)
    from the images directory to generate localized stat gain crops.
    """
    input_dir = Path("images")
    output_dir = Path("output_crops")

    # Create the output directory if it does not already exist.
    if not output_dir.exists():
        output_dir.mkdir(parents=True)

    # Gather and sort all PNG image files from the input directory.
    all_images = sorted(list(input_dir.glob("*.png")))

    # Select the first 30 and last 30 images to process.
    if len(all_images) <= 60:
        target_images = all_images
    else:
        target_images = all_images[:30] + all_images[-30:]

    print(f"Processing {len(target_images)} images...")

    # Execute the cropping logic for each target image.
    for img_file in target_images:
        crop_image_regions(img_file, output_dir)


if __name__ == "__main__":
    main()

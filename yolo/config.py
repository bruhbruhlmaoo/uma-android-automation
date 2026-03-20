"""
Shared configuration for Umamusume YOLOv8 training, export, and testing.
"""

RUN_NAME = "umamusume"
IMGSZ = 128
TOTAL_RUNS = 1
TRAIN_RATIO = 0.8

# Regions for cropping (shared across scripts).
# Format: (x, y, w, h).
STAT_REGIONS = {
    "Speed": [(60, 1615, 130, 50), (60, 1565, 130, 50)],
    "Stamina": [(230, 1615, 130, 50), (230, 1565, 130, 50)],
    "Power": [(400, 1615, 130, 50), (400, 1565, 130, 50)],
    "Guts": [(570, 1615, 130, 50), (570, 1565, 130, 50)],
    "Wit": [(740, 1615, 130, 50), (740, 1565, 130, 50)]
}

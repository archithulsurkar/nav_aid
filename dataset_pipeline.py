"""
nav_aid — Dataset Pipeline & Fine-Tuning Script
=================================================
Run this on your server (or Google Colab / Kaggle) to:

  Step 1: Download COCO128 (quick smoke-test subset)
  Step 2: Pull targeted Open Images V7 classes via fiftyone
  Step 3: Merge datasets into unified YOLO format
  Step 4: Fine-tune YOLOv13n (or YOLOv8n) on the combined set
  Step 5: Validate and export to ONNX for faster CPU inference

Usage:
    python dataset_pipeline.py --step all          # full pipeline
    python dataset_pipeline.py --step download     # just datasets
    python dataset_pipeline.py --step train        # just training
    python dataset_pipeline.py --step export       # just ONNX export
    python dataset_pipeline.py --step validate     # validate current model

Requirements:
    pip install ultralytics fiftyone torch torchvision
"""

from __future__ import annotations

import argparse
import os
import shutil
import sys
import yaml
from pathlib import Path

# ── Paths ─────────────────────────────────────────────────────────────────────
BASE_DIR    = Path(__file__).parent
DATA_DIR    = BASE_DIR / "datasets"
MERGED_DIR  = DATA_DIR / "nav_aid_merged"
MODELS_DIR  = BASE_DIR
RUNS_DIR    = BASE_DIR / "runs"

# Adjust this to point to your yolov13 folder if needed
YOLOV13_DIR = BASE_DIR / "yolov13"
if YOLOV13_DIR.is_dir():
    sys.path.insert(0, str(YOLOV13_DIR))

# ── Target classes ────────────────────────────────────────────────────────────
# These are the Open Images V7 class names we want to pull.
# COCO classes come pre-baked into yolov8n/yolov13n weights.
OPEN_IMAGES_CLASSES = [
    "Stairs",
    "Door",
    "Tree",
    "Wall",
    "Window",
    "Pillar",
    "Railing",
    "Escalator",
    "Elevator",
    "Curb",
    "Ramp",
    "Sidewalk",
    "Street light",
    "Traffic sign",
    "Trash can",
    "Shopping cart",
    "Wheelchair",
]

# ── Nav-aid class list (merged: COCO nav-relevant + Open Images custom) ───────
NAV_CLASSES = [
    # From COCO
    "person", "bicycle", "car", "motorcycle", "bus", "truck",
    "traffic light", "stop sign", "fire hydrant", "parking meter", "bench",
    "chair", "couch", "dining table", "bed", "toilet", "sink",
    "suitcase", "backpack", "handbag", "potted plant", "vase",
    "bottle", "bowl", "cup",
    # From Open Images (structural / navigation)
    "stairs", "door", "tree", "wall", "window", "pillar", "railing",
    "escalator", "elevator", "curb", "ramp", "sidewalk",
    "street light", "traffic sign", "trash can", "shopping cart",
    "wheelchair",
    # From best.pt (already have these, keep for consistency)
    "exit",
]


# ════════════════════════════════════════════════════════════════════════════
#  Step 1 — Download datasets
# ════════════════════════════════════════════════════════════════════════════
def download_datasets(max_oi_samples: int = 500):
    """
    Downloads:
      - COCO128 (128 images, quick test)
      - Open Images V7 targeted classes via fiftyone
    """
    from ultralytics import YOLO

    # ── COCO128 (built-in Ultralytics dataset) ─────────────────────────────
    print("\n── Step 1a: COCO128 download ─────────────────────────────────")
    coco128_dir = DATA_DIR / "coco128"
    if coco128_dir.exists():
        print(f"  Already exists: {coco128_dir}")
    else:
        print("  Downloading COCO128 via Ultralytics...")
        import urllib.request
        url = "https://ultralytics.com/assets/coco128.zip"
        zip_path = DATA_DIR / "coco128.zip"
        DATA_DIR.mkdir(parents=True, exist_ok=True)
        urllib.request.urlretrieve(url, zip_path)
        shutil.unpack_archive(zip_path, DATA_DIR)
        zip_path.unlink()
        print(f"  COCO128 saved to {coco128_dir}")

    # ── Open Images V7 targeted classes ───────────────────────────────────
    print("\n── Step 1b: Open Images V7 targeted classes ──────────────────")
    try:
        import fiftyone as fo
        import fiftyone.zoo as foz

        oi_dir = DATA_DIR / "open_images_nav"
        if oi_dir.exists():
            print(f"  Already exists: {oi_dir}")
        else:
            oi_dir.mkdir(parents=True, exist_ok=True)
            print(f"  Downloading {max_oi_samples} samples per class from Open Images V7...")
            print(f"  Classes: {OPEN_IMAGES_CLASSES}")

            dataset = foz.load_zoo_dataset(
                "open-images-v7",
                split="train",
                label_types=["detections"],
                classes=OPEN_IMAGES_CLASSES,
                max_samples=max_oi_samples,
                dataset_dir=str(oi_dir),
            )
            print(f"  Downloaded {len(dataset)} samples")

            # Export to YOLO format
            export_dir = oi_dir / "yolo_export"
            dataset.export(
                export_dir=str(export_dir),
                dataset_type=fo.types.YOLOv5Dataset,
                label_field="detections",
                classes=OPEN_IMAGES_CLASSES,
            )
            print(f"  Exported YOLO format to {export_dir}")

    except ImportError:
        print("  fiftyone not installed — skipping Open Images.")
        print("  Install with: pip install fiftyone")
    except Exception as e:
        print(f"  Open Images download failed: {e}")
        print("  You can skip this and train on COCO128 first.")

    print("\n✓ Dataset download complete")


# ════════════════════════════════════════════════════════════════════════════
#  Step 2 — Merge datasets into unified YOLO layout
# ════════════════════════════════════════════════════════════════════════════
def merge_datasets():
    """
    Combines COCO128 + Open Images into a single YOLO dataset directory.
    Writes nav_aid.yaml that the trainer reads.
    """
    print("\n── Step 2: Merging datasets ──────────────────────────────────")

    for split in ("images/train", "images/val", "labels/train", "labels/val"):
        (MERGED_DIR / split).mkdir(parents=True, exist_ok=True)

    # ── Copy COCO128 ────────────────────────────────────────────────────────
    coco128 = DATA_DIR / "coco128"
    if coco128.exists():
        _copy_split(coco128 / "images" / "train2017",
                    MERGED_DIR / "images" / "train", prefix="coco_")
        _copy_split(coco128 / "labels" / "train2017",
                    MERGED_DIR / "labels" / "train", prefix="coco_")
        print("  ✓ COCO128 merged")

    # ── Copy Open Images export ─────────────────────────────────────────────
    oi_export = DATA_DIR / "open_images_nav" / "yolo_export"
    if oi_export.exists():
        _copy_split(oi_export / "images" / "train",
                    MERGED_DIR / "images" / "train", prefix="oi_")
        _copy_split(oi_export / "labels" / "train",
                    MERGED_DIR / "labels" / "train", prefix="oi_")
        _copy_split(oi_export / "images" / "validation",
                    MERGED_DIR / "images" / "val", prefix="oi_")
        _copy_split(oi_export / "labels" / "validation",
                    MERGED_DIR / "labels" / "val", prefix="oi_")
        print("  ✓ Open Images merged")

    # ── Copy best.pt training data if it exists ─────────────────────────────
    custom_dir = BASE_DIR / "custom_data"
    if custom_dir.exists():
        _copy_split(custom_dir / "images" / "train",
                    MERGED_DIR / "images" / "train", prefix="custom_")
        _copy_split(custom_dir / "labels" / "train",
                    MERGED_DIR / "labels" / "train", prefix="custom_")
        print("  ✓ Custom glasses data merged")

    # ── Write dataset YAML ──────────────────────────────────────────────────
    yaml_path = MERGED_DIR / "nav_aid.yaml"
    dataset_config = {
        "path":  str(MERGED_DIR),
        "train": "images/train",
        "val":   "images/val",
        "nc":    len(NAV_CLASSES),
        "names": NAV_CLASSES,
    }
    with open(yaml_path, "w") as f:
        yaml.dump(dataset_config, f, default_flow_style=False)

    n_train = len(list((MERGED_DIR / "images" / "train").glob("*")))
    n_val   = len(list((MERGED_DIR / "images" / "val").glob("*")))
    print(f"  Dataset YAML written to {yaml_path}")
    print(f"  Train images: {n_train} | Val images: {n_val}")
    print("\n✓ Merge complete")
    return yaml_path


def _copy_split(src: Path, dst: Path, prefix: str = "") -> None:
    if not src.exists():
        return
    for f in src.iterdir():
        if f.is_file():
            shutil.copy2(f, dst / f"{prefix}{f.name}")


# ════════════════════════════════════════════════════════════════════════════
#  Step 3 — Fine-tune
# ════════════════════════════════════════════════════════════════════════════
def train(
    base_model: str = "yolov13n.pt",
    epochs: int = 50,
    imgsz: int = 640,
    batch: int = 8,
    device: str = "cpu",
):
    """
    Fine-tunes base_model on the merged nav_aid dataset.
    Use device='cuda' or device='mps' if you have GPU/Apple Silicon.
    On CPU, keep epochs low (10-20) and use imgsz=416 for speed.
    """
    from ultralytics import YOLO

    yaml_path = MERGED_DIR / "nav_aid.yaml"
    if not yaml_path.exists():
        print("Dataset not found — run --step merge first")
        return

    model_path = MODELS_DIR / base_model
    if not model_path.exists():
        print(f"Base model not found: {model_path}")
        return

    print(f"\n── Step 3: Fine-tuning {base_model} ─────────────────────────")
    print(f"  Dataset: {yaml_path}")
    print(f"  Epochs:  {epochs} | imgsz: {imgsz} | batch: {batch} | device: {device}")

    model = YOLO(str(model_path))
    results = model.train(
        data=str(yaml_path),
        epochs=epochs,
        imgsz=imgsz,
        batch=batch,
        device=device,
        project=str(RUNS_DIR),
        name="nav_aid_finetune",
        exist_ok=True,
        patience=15,          # early stopping
        save=True,
        plots=True,
        # Augmentation tuned for first-person walking footage
        degrees=5.0,          # small rotation — camera tilts slightly
        translate=0.1,
        scale=0.4,
        flipud=0.0,           # never flip up-down — sky stays sky
        fliplr=0.3,
        mosaic=0.8,
        mixup=0.1,
        hsv_h=0.015,
        hsv_s=0.5,
        hsv_v=0.3,
    )

    best_weights = RUNS_DIR / "nav_aid_finetune" / "weights" / "best.pt"
    output_path  = MODELS_DIR / "nav_aid_best.pt"
    if best_weights.exists():
        shutil.copy2(best_weights, output_path)
        print(f"\n✓ Best weights saved to {output_path}")
    else:
        print("\n  Training complete (check runs/nav_aid_finetune/weights/)")

    return results


# ════════════════════════════════════════════════════════════════════════════
#  Step 4 — Validate
# ════════════════════════════════════════════════════════════════════════════
def validate(model_path: str = "nav_aid_best.pt"):
    from ultralytics import YOLO

    yaml_path = MERGED_DIR / "nav_aid.yaml"
    mp        = MODELS_DIR / model_path
    if not mp.exists():
        mp = MODELS_DIR / "yolov13n.pt"

    print(f"\n── Validating {mp} ───────────────────────────────────────────")
    model   = YOLO(str(mp))
    metrics = model.val(data=str(yaml_path), verbose=True)
    print(f"\n  mAP50:    {metrics.box.map50:.4f}")
    print(f"  mAP50-95: {metrics.box.map:.4f}")
    print(f"  Precision:{metrics.box.mp:.4f}")
    print(f"  Recall:   {metrics.box.mr:.4f}")
    return metrics


# ════════════════════════════════════════════════════════════════════════════
#  Step 5 — Export to ONNX
# ════════════════════════════════════════════════════════════════════════════
def export_onnx(model_path: str = "nav_aid_best.pt", imgsz: int = 640):
    from ultralytics import YOLO

    mp = MODELS_DIR / model_path
    if not mp.exists():
        mp = MODELS_DIR / "yolov13n.pt"
        print(f"  nav_aid_best.pt not found, exporting {mp}")

    print(f"\n── Exporting {mp} to ONNX ────────────────────────────────────")
    model = YOLO(str(mp))
    path  = model.export(
        format="onnx",
        imgsz=imgsz,
        simplify=True,
        opset=12,
    )
    print(f"\n✓ ONNX model saved to {path}")
    print("  Use this in the server for faster CPU inference.")
    return path


# ════════════════════════════════════════════════════════════════════════════
#  CLI
# ════════════════════════════════════════════════════════════════════════════
def main():
    parser = argparse.ArgumentParser(description="nav_aid dataset pipeline")
    parser.add_argument(
        "--step",
        choices=["download", "merge", "train", "validate", "export", "all"],
        default="all",
        help="Which step to run",
    )
    parser.add_argument("--model",  default="yolov13n.pt", help="Base model filename")
    parser.add_argument("--epochs", type=int,   default=50)
    parser.add_argument("--imgsz",  type=int,   default=640)
    parser.add_argument("--batch",  type=int,   default=8)
    parser.add_argument("--device", default="cpu", help="cpu / cuda / mps")
    parser.add_argument("--oi-samples", type=int, default=500,
                        help="Max Open Images samples per class")
    args = parser.parse_args()

    if args.step in ("download", "all"):
        download_datasets(max_oi_samples=args.oi_samples)

    if args.step in ("merge", "all"):
        merge_datasets()

    if args.step in ("train", "all"):
        train(
            base_model=args.model,
            epochs=args.epochs,
            imgsz=args.imgsz,
            batch=args.batch,
            device=args.device,
        )

    if args.step in ("validate", "all"):
        validate()

    if args.step in ("export", "all"):
        export_onnx()


if __name__ == "__main__":
    main()

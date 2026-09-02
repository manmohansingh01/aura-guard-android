package com.auraguard.app.ai

import com.auraguard.app.core.ObjectClass

/**
 * Standard 80-class COCO label set, which is what almost every publicly
 * available pretrained YOLO-family .tflite export (YOLOv5/v8 "coco"
 * weights) is trained on. AURA Guard only *surfaces* the classes relevant
 * to perimeter security (see [RELEVANT_CLASSES]); everything else the
 * model detects is ignored downstream.
 */
object CocoLabels {
    val NAMES = listOf(
        "person", "bicycle", "car", "motorcycle", "airplane", "bus", "train", "truck", "boat",
        "traffic light", "fire hydrant", "stop sign", "parking meter", "bench", "bird", "cat",
        "dog", "horse", "sheep", "cow", "elephant", "bear", "zebra", "giraffe", "backpack",
        "umbrella", "handbag", "tie", "suitcase", "frisbee", "skis", "snowboard", "sports ball",
        "kite", "baseball bat", "baseball glove", "skateboard", "surfboard", "tennis racket",
        "bottle", "wine glass", "cup", "fork", "knife", "spoon", "bowl", "banana", "apple",
        "sandwich", "orange", "broccoli", "carrot", "hot dog", "pizza", "donut", "cake", "chair",
        "couch", "potted plant", "bed", "dining table", "toilet", "tv", "laptop", "mouse",
        "remote", "keyboard", "cell phone", "microwave", "oven", "toaster", "sink", "refrigerator",
        "book", "clock", "vase", "scissors", "teddy bear", "hair drier", "toothbrush"
    )

    /** COCO class index -> AURA Guard security-relevant class. Absent entries are ignored. */
    val RELEVANT_CLASSES: Map<Int, ObjectClass> = mapOf(
        0 to ObjectClass.PERSON,
        1 to ObjectClass.BICYCLE,
        2 to ObjectClass.CAR,
        3 to ObjectClass.MOTORCYCLE,
        7 to ObjectClass.TRUCK
    )
}

package com.auraguard.app.ai

import com.auraguard.app.core.ObjectClass

/**
 * VisDrone2019-DET's 10-class label set. Unlike [CocoLabels] (street-level
 * photos), VisDrone is real drone-captured footage — 288 video clips plus
 * ~10k images shot from UAVs over 14 cities, angles ranging from near-nadir
 * (straight down) to oblique — which is why fine-tuning on it (see
 * /colab/train_yolo26_visdrone.ipynb) rather than using stock COCO weights
 * is what actually fixes overhead-view detection accuracy.
 *
 * [TFLiteObjectDetector] auto-selects this label set purely by output shape
 * (10 classes rather than COCO's 80) once a model is loaded, so dropping in
 * a VisDrone-trained export just works — no app config change needed.
 */
object VisDroneLabels {
    val NAMES = listOf(
        "pedestrian", "people", "bicycle", "car", "van",
        "truck", "tricycle", "awning-tricycle", "bus", "motor"
    )

    /** VisDrone class index -> AURA Guard security-relevant class. Every class maps to something. */
    val RELEVANT_CLASSES: Map<Int, ObjectClass> = mapOf(
        0 to ObjectClass.PERSON,      // pedestrian
        1 to ObjectClass.PERSON,      // people (crowd/standing)
        2 to ObjectClass.BICYCLE,     // bicycle
        3 to ObjectClass.CAR,         // car
        4 to ObjectClass.CAR,         // van
        5 to ObjectClass.TRUCK,       // truck
        6 to ObjectClass.BICYCLE,     // tricycle
        7 to ObjectClass.BICYCLE,     // awning-tricycle
        8 to ObjectClass.TRUCK,       // bus
        9 to ObjectClass.MOTORCYCLE   // motor
    )
}

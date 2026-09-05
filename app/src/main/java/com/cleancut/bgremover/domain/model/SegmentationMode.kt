package com.cleancut.bgremover.domain.model

/**
 * Segmentation quality and engine modes.
 */
enum class SegmentationMode {
    /**
     * Fast on-device segmentation via ML Kit + Guided Filter edge refinement.
     * Near instant (30-60ms) and zero additional storage.
     */
    FAST,

    /**
     * Studio-grade matting using RMBG-1.4 via ONNX Runtime Mobile.
     * High precision for hair, fur, and intricate boundaries.
     */
    STUDIO
}

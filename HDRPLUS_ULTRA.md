# HDR+ Ultra

Android RAW burst camera derived from PhotonCamera and informed by the HDR+
paper, "Burst photography for high dynamic range and low-light imaging on
mobile cameras".

## Capture protocol

1. Capture a user-selected number of normal RAW denoising frames at constant exposure.
2. Capture 0-8 short RAW frames at the same ISO. Their shutter reduction is
   adjustable from 1 to 8 EV and clamped to the sensor's advertised minimum.
3. Capture 0-8 long RAW frames at the same ISO. Their shutter increase is
   adjustable from 1 to 8 EV and clamped to the sensor's advertised maximum.
4. Use the shortest sharp frame as the radiometric highlight reference.
5. Normalize and robustly align the normal and long frames to the reference in the
   existing GPU Bayer fusion pipeline.
6. Demosaic once, tone map, and optionally encode an Android Ultra HDR gain map.

The highlight-recovery control ranges from 0 to 200. At 0, short RAW frames are
removed before alignment and fusion. At 100, the balanced HDR rejection threshold
and adaptive SDR shoulder are used. At 200, the short exposure is retained deeper
into bright regions and the tone shoulder uses its strongest recovery curve.

The processing-accelerator selector controls FlowNet and KernelNet inference:
Auto, CPU, GPU/Vulkan, or an experimental NPU request. The main RAW fusion,
demosaic and tone pipeline remain OpenGL compute workloads. Because bundled ncnn
does not expose an Android NPU backend, an NPU request is explicitly reported in
logcat and falls back to Vulkan (or CPU on a non-Vulkan ABI) rather than silently
claiming that the NPU ran.

The short frame is intentionally not averaged as if it had the same exposure.
Its exposure product is tracked by timestamp and used for radiometric scaling,
which preserves highlight information while reducing ghosting risk.

## License

This is a modified version of PhotonCamera and remains licensed under GPL-3.0.
See `LICENSE` and the upstream project at https://github.com/eszdman/PhotonCamera.

# NICE forward network on Hexagon v75 (Snapdragon 8 Gen 3)

vivo's original context (`nice-main-forward-v79.bin`) only loads on SM8750 (HTP v79). Other SoCs run a
distilled fp16 student with the same contract (input `inputs_0` f32 1x544x544x22, output `tail_conv_1_0` f32 1x544x544x3).

* Student: 5-level UNet (16/32/64/128/128), output = learnable 9x9 conv of the input (init: box filter of
  channels 15..17) + residual. Trained with L1 against 132 teacher tiles recorded from the vivo forward
  network (val L1 0.0097; box9 baseline 0.0105, identity 0.017).
* Export: fp16 QNN graph compiled on the device with QAIRT 2.28 (`nice-student-v75.bin`, ~3 MB); ~50 ms per tile
  on the Oppo Find X7 Ultra (48 tiles per shot, ~2.5 s inference). NPU output matches PyTorch to 3e-4 L1.
* Runtime: `Graph` in `vivo-nice-probe.cpp` picks the student when `ro.soc.model != SM8750` and loads
  `libQnnHtp228.so` (QAIRT 2.28 libQnnHtp.so) + `libQnnHtpV75Stub.so` / `libQnnHtpV75Skel.so`.
  Assets are listed in `VivoNeuralWorker.NICE75_FILES` and packaged by `tools/package_vivo_neural.py`.
* The int8 vivo weights were decoded (research only), but exact float emulation of its requantization
  was not achieved, hence distillation.

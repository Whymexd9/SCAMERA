# Encrypted neural asset seed v2

AES-256-GCM encrypted packaging resources for SCAMERA Actions (bundle v1 plus the CRE motion
runtime and the Quad/VSR contexts). The decryption key is kept separately in the repository Actions
secret SCAMERA_NEURAL_ASSETS_KEY_V2. Plaintext and per-file SHA-256 are pinned and verified by the source
workflow. This branch contains no plaintext model/runtime binaries and no key. Do not delete it: CI
bootstraps from it when no v2 artifact is left.

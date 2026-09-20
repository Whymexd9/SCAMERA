# Encrypted neural asset seed

AES-256-GCM encrypted packaging resources for SCAMERA Actions.
The decryption key is kept separately in repository Actions secrets.
Plaintext and per-file SHA-256 are pinned and verified by the source workflow.
This branch contains no plaintext model/runtime binaries and no key.

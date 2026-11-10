## v1.0.0

### Features

- scan a queue of APKs one at a time ([2c5052b](https://github.com/Lynxifyy/Bedrock-Offsets/commit/2c5052bd55af60b6a99ffd79005d7be15ad4a4f4))
- polish the states, the splash and the feedback ([0b1133e](https://github.com/Lynxifyy/Bedrock-Offsets/commit/0b1133e32660ff413a5dcd63d19d682cb460a796))
- survive memory pressure and hostile inputs ([119f977](https://github.com/Lynxifyy/Bedrock-Offsets/commit/119f977141766cef4901a86358edd4c904044f8f))
- cross-check offsets against the library's symbol table ([ba769c7](https://github.com/Lynxifyy/Bedrock-Offsets/commit/ba769c783369fdd81a3a6f570c9ab3b36aeab44a))
- structured live log with tags, colour and bounded history ([dec79c1](https://github.com/Lynxifyy/Bedrock-Offsets/commit/dec79c146e2e947dff0bcdcbfe482be27aa93242))
- cache scan results keyed by build identifier ([c8fa25c](https://github.com/Lynxifyy/Bedrock-Offsets/commit/c8fa25c2a25d0402b6571f34a10328daddb0817a))
- export offsets to device storage ([1c8aace](https://github.com/Lynxifyy/Bedrock-Offsets/commit/1c8aace1b1cc9e67cb47a121fe3fa5451f1a8114))
- results panel with grouped offsets and filtering ([8b0994a](https://github.com/Lynxifyy/Bedrock-Offsets/commit/8b0994aedac19f7ce9c57b330cadb4107f076e08))
- report scan progress from the engine to the UI ([05fa693](https://github.com/Lynxifyy/Bedrock-Offsets/commit/05fa693df3c2ec3b4f5806a100373cf2ff5f227d))
- resolve RTTI vtables from the relocation table ([4adb2f0](https://github.com/Lynxifyy/Bedrock-Offsets/commit/4adb2f0cc1ee0606837ff0ead9597a00d593ec46))
- verify function prologues with Capstone ([516b202](https://github.com/Lynxifyy/Bedrock-Offsets/commit/516b2029dfb498a64409afe414aa6464726b4554))
- add the Aho-Corasick signature scanner ([eb11b14](https://github.com/Lynxifyy/Bedrock-Offsets/commit/eb11b14499a6696838585356337b0bec9f6b2f42))
- extract the build id and exported JNI symbols ([e38a536](https://github.com/Lynxifyy/Bedrock-Offsets/commit/e38a5360cc2209870045f9231c7b2e7f5813f586))
- read section headers and map virtual addresses ([a076475](https://github.com/Lynxifyy/Bedrock-Offsets/commit/a076475b7c81555054a2ad7316e68819c6a539fe))
- map the native library with zero-copy mmap ([e8388aa](https://github.com/Lynxifyy/Bedrock-Offsets/commit/e8388aa08a66078fc9efdf3778071e3f174011e6))
- add the Rust NDK JNI bridge ([16b39c7](https://github.com/Lynxifyy/Bedrock-Offsets/commit/16b39c7d51d6e067f48d67f1690e0d53ddc385d5))
- add async ViewModel with typed error mapping ([aa7bd67](https://github.com/Lynxifyy/Bedrock-Offsets/commit/aa7bd6711351009d75c7f993c3af635773d6bf07))
- add streaming ZIP extractor in the Rust core engine ([c62aeea](https://github.com/Lynxifyy/Bedrock-Offsets/commit/c62aeea087ee432052c65cc460863018f3b65823))
- add zero-copy SAF file picker for APK selection ([52f527a](https://github.com/Lynxifyy/Bedrock-Offsets/commit/52f527a73b08f22b9a39b5e43781665592af7472))
- add Compose shell, caramel theme and live log pane ([8232566](https://github.com/Lynxifyy/Bedrock-Offsets/commit/82325667f1c51ad248c02bf31abfc8893f61a111))
- scaffold Android Compose project with CI and R8 rules ([634f2fc](https://github.com/Lynxifyy/Bedrock-Offsets/commit/634f2fcdc553da84813a0a3f2571e428516588d3))

### Fixes

- harden the boundary between the engine and the device ([463d20f](https://github.com/Lynxifyy/Bedrock-Offsets/commit/463d20fd4b460360ea9cc38f9be06afb1bcc472e))

### Performance

- keep the release build small and provable ([93eabfc](https://github.com/Lynxifyy/Bedrock-Offsets/commit/93eabfc3855084a93fc69a788e4d945cba2f0352))

### Documentation

- explain the engine, the output format and the failure modes ([f8ce23a](https://github.com/Lynxifyy/Bedrock-Offsets/commit/f8ce23a6b8e035de48399ed5e96f6f1a36965c6d))

### Build

- automate the offset tooling in Python ([cda60b1](https://github.com/Lynxifyy/Bedrock-Offsets/commit/cda60b11008426ec9f8c37144c7fe4d1078a3a73))

### Other

- add reproducible local toolchain setup script ([ec315f3](https://github.com/Lynxifyy/Bedrock-Offsets/commit/ec315f3d125318b1ab281e555ac903a49d2b437b))

### Other

- Add greeting to Readme.md ([efb78d8](https://github.com/Lynxifyy/Bedrock-Offsets/commit/efb78d8308a1ab049ce3069650049f49dd435dde))

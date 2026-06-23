# Tree-sitter WASM asset sources

Date obtained: 2026-06-23

These prebuilt WebAssembly modules drive the universal (tree-sitter based)
parsing path. They were pulled from npm; no local build toolchain
(`tree-sitter` CLI / `emcc`) was used.

## `tree-sitter.wasm` — web-tree-sitter CORE module

- npm package: `web-tree-sitter@0.22.6`
- Tarball: `web-tree-sitter-0.22.6.tgz` (shasum `0f47fa798a5cbe0c2293b813f51c3ddebf94abbe`)
- File inside tarball: `package/tree-sitter.wasm` (188,635 bytes)
- Registry URL: https://registry.npmjs.org/web-tree-sitter/-/web-tree-sitter-0.22.6.tgz
- Project: https://www.npmjs.com/package/web-tree-sitter

## `tree-sitter-python.wasm` — Python GRAMMAR

- npm package: `tree-sitter-wasms@0.1.13` (latest; `dist-tags.latest = 0.1.13`)
- Tarball: `tree-sitter-wasms-0.1.13.tgz` (shasum `0502852881d40d0af5720f11d75b348796d8ce20`)
- File inside tarball: `package/out/tree-sitter-python.wasm` (476,105 bytes)
- Registry URL: https://registry.npmjs.org/tree-sitter-wasms/-/tree-sitter-wasms-0.1.13.tgz
- Project: https://github.com/Gregoor/tree-sitter-wasms
- Built (per that package's devDependencies) with `tree-sitter-cli@^0.20.8`
  and `tree-sitter-python@^0.21.0`.

## ABI / LANGUAGE_VERSION compatibility (IMPORTANT)

- The Python grammar's tree-sitter ABI **LANGUAGE_VERSION = 14** (read at
  runtime via `Language.load(...).version`).
- The CORE version was chosen deliberately, NOT picked as "latest":
  - The latest core, `web-tree-sitter@0.26.9`, FAILS to load this ABI-14
    grammar — it throws inside `getDylinkMetadata` (emscripten dylink ABI
    mismatch between the new core and the older grammar build).
  - Cores `0.22.6`, `0.22.2`, `0.21.0`, and `0.20.8` all successfully load
    AND parse the grammar (verified: `def f(x): return x + 1` ->
    `(module (function_definition name: (identifier) parameters: ...))`).
  - `0.22.6` was selected as the newest core that is verified compatible
    with the ABI-14 grammar from `tree-sitter-wasms@0.1.13`.

Compatibility was verified empirically in Node (web-tree-sitter API) by
loading the grammar with each candidate core and parsing a sample. Full
verification through the JVM (Chicory + at.released.weh emscripten bindings)
is a later task.

## Replacing / upgrading later

If a newer core is needed, the Python grammar must be rebuilt against a
matching tree-sitter ABI (e.g. via a newer `tree-sitter-wasms` release, or
`tree-sitter build --wasm` with a contemporary `tree-sitter` CLI). Keep the
core LANGUAGE_VERSION range covering the grammar's LANGUAGE_VERSION.

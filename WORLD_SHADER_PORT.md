# Native world shader extension ownership

`NativePipelineBridge.register` augments genuine immutable RenderPearl pipeline definitions, retaining their shader IDs, defines, vertex bindings, topology, attachment formats, blend/depth/cull state and push constants. Typed UBO/combined-image-sampler declarations are appended. The real native cache retains and retires the real compiled pipeline; the bridge does not install a wrapper compiled object or own borrowed extension resources.

Both vanilla's eager ShaderManager compilation and lazy PipelineCache compilation use the actual source resource owner. AST processors receive `MinecraftContext.pipeline()` and the actual shader source `Context.name()`. `shaderInstance()` is the actual pipeline identifier. Root/include contexts remain distinct. Match pipeline identity or source IDs explicitly instead of treating the pipeline name as a source name.

Native source includes execute at their lexical position on the genuine bundled C preprocessor's source stack. This preserves macro visibility, declaration order, conditional import exclusion, header guards, repeated includes and explicit pragma once. Import AST callbacks run on expanded include regions before the root callback. Actual RenderPearl device macro flags are available before conditionals are eliminated. No shader compile failure falls back to unmodified source.

The resource supplier runs after the actual frontend render pass selects its genuine compiled pipeline. It must return only existing, prepared, borrowed buffer slices and texture/sampler references. Allocate resources, upload values, create encoders and submit prepasses before opening that pass. Prepare valid disabled/default bindings too: an enabled/disabled uniform can select behavior, but every declared binding still requires a live typed resource. Owners retain their resources through submission and release them on reload/shutdown.

Sable registers real water and sky extensions through this API. Its frame-head preparation creates disabled UBOs, cleared zero-depth views and nearest/clamp samplers before world/GUI passes. Enabled prepasses provide the current masks and copied UBO slices. Actual Vulkan acceptance covers vanilla/OIT compilation, per-pass independent bindings, water discard, sky lightmap changes, disabled frames and resource reload. The test-only entrypoints do not initialize the complete Veil or Sable mods.

Run only affected scopes:

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home \
  python3 ports/veil/scripts/verify-gpu.py --api
JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home \
  python3 ports/veil/scripts/verify-gpu.py --client --checks bridge
JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home \
  python3 ports/sable/scripts/check_native_world_shaders_gpu.py
```

The downstream test requires Sable's complete genuine diagnostic classes first. Complete production Veil compilation/startup and broader world lifecycle integration remain separate outstanding work; no complete Veil runtime artifact exists.

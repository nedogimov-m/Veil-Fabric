# Native render types on Minecraft 26.3

This component milestone uses actual Minecraft 26.3 RenderSetup, RenderPipeline, PreparedRenderType, RenderPearl and Fabric mixins. It does not certify or package the complete Veil mod. The remaining production renderer, dynamic/fixed buffer and other legacy consumers must compile and start before that claim is possible.

## Implemented behavior

- All nineteen resource layer types build typed immutable native state. Shader IDs, textures, independent blur/mipmap flags, blend/depth/cull state, lightmap/overlay, layering, outputs, texture transforms and write masks retain explicit ownership. Legacy depth comparisons account for native reversed Z. The exact crumbling blend is retained.
- Existing native types can be transformed without discarding their shader defines/layouts, vertex bindings, arbitrary texture sampler names, setup flags, transforms or outline. Named, concrete and filtered modifiers apply to existing and future native types through actual constructor/setup mixins; derived blending and outline state update together.
- Native wrappers and layered types prepare real native geometry. Layers replay the same staged indexed mesh in order. Managed Veil shader or alternate-output draws defer until END_MAIN, where separate native passes and transient uploads are legal. Native-only types retain the caller's pass.
- Deferred draws capture copied scissor state, owned UBO bytes, selected serialized global ShaderBlock values, external buffer references, textures and program leases. Leases survive shader retirement and remain held until the encoder is submitted. Serialized values remain immutable through Java-object mutation and block disposal. Borrowed buffer contents and lifetime remain the caller's responsibility. The owning renderer binds the runtime/blocks, flushes at END_MAIN and releases pending owners before shutdown; an explicit non-level owner may use `try (var submission = NativeRenderTypeDraws.flush(encoder)) { encoder.submit(); }`.
- Resource codecs retain single/multiple layer forms, topology names and the independent crumbling flag. Custom vertex layouts encode real attribute formats, offsets, padding, stride and instance step rate. Parameterized resource keys deep-copy nested arrays and compare values rather than only hashes. Reload/eviction release retired native variants.
- The exact native RenderType constructor is opened by one production access-widener entry. Removed GL shard implementations are retained with real replacement mappings in [upstream-retired/rendertype](upstream-retired/rendertype/REPLACEMENTS.md).

## Separate verification scopes

`scripts/verify-rendertypes.py` compiles 37 genuine production sources and two explicit test entrypoints against cached Java 25 / Minecraft 26.3 / Fabric APIs. It applies the exact constructor access through Fabric ClassTweaker, reuses the already compiled genuine GPU boundary and builds an explicitly test-only acceptance mod. It does not change the normal source set or rerun the prior GPU groups.

| Scope | Result and evidence |
| --- | --- |
| Resource codecs and cache keys | Passed `native_resource_codecs_and_cache_keys`: custom padded/instanced vertex layout roundtrip, topology, single-layer encoding, crumbling independent of sort, blur independent of mipmap, reversed-Z mapping and colliding/deep-copied parameter keys. `rendertype-runtime-fixed-codec.log` records this passed scope; that combined run subsequently failed while entering the then-broken layer shader fixture. |
| Parameterized resource manager | Passed `parameterized_resource_manager` in the same log: real resource-defined types retain distinct colliding values and reuse stable keys. Its later unrelated layer failure is not a successful combined-run result. |
| Native layers and output | Passed separately on actual Vulkan: `same_staged_mesh_ordered_layers_and_explicit_output`, `rendertype-layer-runtime-passed.log`. One staged indexed mesh rendered red then translucent blue to the selected target, yielding `(128,0,128,255)`; the caller's scratch pass remained transparent black. Actual factory, prepare and draw mixins executed. |
| Native generic modifiers | Passed separately on actual Vulkan: `existing_future_filtered_native_setup_modifiers`, `rendertype-generic-runtime.log`. Verified existing/future named types, filtered masks/depth, arbitrary samplers, lightmap/overlay/solid/crumbling/sort/outline preservation, derived blending updates and managed shader/output ownership. |
| Serialized global blocks | Passed separately on actual Vulkan: `deferred_render_type_captures_serialized_global_blocks`, `rendertype-block-snapshots-runtime.log` and `build/rendertype-verification/blocks-{runtime,inputs}.json`. Actual prepared draw hooks capture different values from one mutable block, then render red/blue to separate targets after that block is freed. The caller target stays transparent black. Resource JSON declares the native external UBO with `uniform_blocks`. |

The latest `build/rendertype-verification/runtime.json` contains only the most recent selected scope. Historical logs above preserve independent results; they must not be reported as one all-groups pass. The generic compile input hashes and report are also preserved as `generic-inputs.json` and `generic-runtime.json` beside that file. There is no earlier exact source-hash snapshot for the separate codecs/cache or layer runs.

Only rerun an affected selector after changing its behavior:

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home \
  python3 ports/veil/scripts/verify-rendertypes.py --client --checks generic
```

Selectors are `codecs`, `cache`, `layers`, `generic`, `blocks`. A compile-only run omits `--client`. Subsequent client runs preserve selected reports and input hashes automatically.

## Remaining integration and capabilities

The production VeilRenderer now binds the managers, dynamic type manager and selected global block state, registers the END_MAIN draw callback, and releases pending owners before shutdown. The complete renderer still fails compilation, so this wiring has not executed in a complete Veil launch. Focused tests explicitly bind/flush their own runtime. Non-level/GUI owners need a valid submission owner too. VeilClient still has old DynamicBufferShard registration and a removed CompositeRenderType signature. The production rendertype mixin configuration still references RenderTypeBufferSourceAccessor, whose MultiBufferSource target was removed. These are pending real integrations, not covered by the focused client fixture.

Front-only culling, tessellation patches, depth clamp, multisampled attachments, wide lines without geometry expansion and general bitwise framebuffer operations require backend support or replacement algorithms and currently fail with explicit capability errors. COPY, CLEAR and NOOP are handled explicitly. Native setup has no old output-shard tag: an unspecified native output is described as `current_target`; callers must not infer a physical main framebuffer from that label.

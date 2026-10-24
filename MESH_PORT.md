# Native reusable meshes

`VertexArray` and `VertexArrayBuilder` now use Minecraft 26.3 RenderPearl buffers,
named vertex formats and explicit command encoders/render passes. The source set
still includes every remaining old caller. No complete Veil distribution is built.

```java
try (var mesh = VertexArray.create(device, "model")) {
    var encoder = device.createCommandEncoder();
    mesh.upload(encoder, realMinecraftMeshData, VertexArray.DrawUsage.STATIC);
    // Additional native instance bindings may use VertexFormat.builder(1).
    mesh.uploadVertexBuffer(encoder, 1, instanceBytes, instanceFormat, VertexArray.DrawUsage.DYNAMIC);
    try (var draw = mesh.retain()) {
        draw.validatePipeline(nativePipelineDefinition);
        program.prepare(encoder);
        try (var pass = framebuffer.beginPass(encoder)) {
            program.bind(pass);
            draw.drawInstanced(pass, instanceCount);
        }
        encoder.submit();
    }
}
```

Upload consumes/cleans the real Minecraft `MeshData`. Explicit sorted indices are
copied; missing indices use Minecraft's actual sequential quad/line patterns.
Index types are native SHORT/INT. Named native formats retain explicit offsets,
integer/normalized formats, stride and instance step rate. Replacements allocate
independent native storage. A retained `Draw` owns its binding selection through
replacement and mesh retirement; close it after the encoder is submitted.

Borrowed bindings use `editFormat().defineVertexBuffer(binding, slice, format)`
and `setIndexBuffer(slice, type, count)`. Their content/lifetime remains the
external owner's responsibility, including while a Draw is retained. Nonindexed
meshes use `setVertexCount` and `setDrawMode`; owned uploads also set vertex count.
Direct, instanced and tightly packed native indirect commands all use the caller's
actual render pass. Indirect indexed commands require a full index-buffer origin,
since firstIndex is part of the native command structure.

`Draw.drawWithRenderType(pass, type)` uses actual native prepare/layer hooks for
single-binding indexed meshes. Keep that Draw alive through any deferred type
queue's final submission. Multiple instance bindings use an explicit compatible
native pipeline. There are no raw integer buffer IDs or global setup/clear/bind
callbacks. Source usage hints remain available; RenderPearl storage/upload flags
replace the old OpenGL driver allocation hints.

Actual Apple M4 / Vulkan / MoltenVK acceptance passed independently with
`scripts/verify-gpu.py --client --checks meshes`: real quad MeshData, red/blue and
green/yellow instance pixels, direct and indexed/nonindexed indirect commands,
old/new storage surviving replacement and owner retirement, rejected partial index
upload preserving storage, final lease disposal and borrowed buffer ownership.
Evidence: `gpu-mesh-runtime.log`, `build/gpu-verification/meshes-runtime.json` and
`meshes-inputs.json`. No earlier GPU groups were rerun.

The original two public APIs and six superseded GL backend implementations are
preserved in [upstream-retired/vertex](upstream-retired/vertex/README.md). Reference
audit found no remaining live implementation-name callers. Old public-API
consumers in Flare, Quasar, lighting and Necromancer still require migration.

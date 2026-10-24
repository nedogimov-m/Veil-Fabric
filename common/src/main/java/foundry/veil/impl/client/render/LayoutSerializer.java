package foundry.veil.impl.client.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.device.GpuDevice;
import foundry.veil.api.client.render.VeilShaderBufferLayout;
import foundry.veil.api.client.render.shader.block.ShaderBlock;
import foundry.veil.impl.client.render.shader.block.LayoutShaderBlockImpl;
import io.github.ocelot.glslprocessor.api.grammar.GlslTypeSpecifier;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

/** Deterministic std140 layout; native pipelines never reflect GL program object IDs. */
public record LayoutSerializer<T>(List<Field<T>> fields, int size) implements BiConsumer<T, ByteBuffer> {
    public LayoutSerializer { fields = List.copyOf(fields); }
    public static <T> LayoutShaderBlockImpl<T> create(VeilShaderBufferLayout<T> layout) {
        return create(layout, RenderSystem.getDevice());
    }
    public static <T> LayoutShaderBlockImpl<T> create(VeilShaderBufferLayout<T> layout, GpuDevice device) {
        LayoutSerializer<T> serializer = createSerializer(layout);
        return new LayoutShaderBlockImpl<>(device, layout.binding(), serializer.size(), serializer);
    }
    public static <T> LayoutSerializer<T> createSerializer(VeilShaderBufferLayout<T> layout) {
        if (layout.memoryLayout() != ShaderBlock.MemoryLayout.STD140) {
            throw new UnsupportedOperationException("Native uniform blocks require explicit std140 layout: " + layout.memoryLayout());
        }
        if (layout.binding() != ShaderBlock.BufferBinding.UNIFORM) throw new UnsupportedOperationException("Writable storage block needs a native algorithm migration");
        List<Field<T>> fields = new ArrayList<>();
        int offset = 0, blockAlignment = 16;
        for (var field : layout.structSpecifier().getFields()) {
            if (!(field.getType().getSpecifier() instanceof GlslTypeSpecifier.BuiltinType type)) {
                throw new IllegalArgumentException("Nested/array block types need an explicit std140 schema: " + field.getName());
            }
            String name = type.getName();
            int scalarBytes = type.isDouble() ? 8 : 4;
            int alignment, bytes;
            if (type.isMatrix()) {
                String dimensions = name.substring(name.indexOf("mat") + 3);
                int columns = dimensions.charAt(0) - '0';
                int rows = dimensions.length() == 1 ? columns : dimensions.charAt(2) - '0';
                int columnAlignment = rows >= 3 ? 4 * scalarBytes : rows * scalarBytes;
                alignment = align(columnAlignment, 16);
                int stride = align(rows * scalarBytes, alignment);
                bytes = Math.multiplyExact(columns, stride);
            } else if (type.isPrimitive() || type.isVector()) {
                int components = type.getComponents();
                alignment = (components >= 3 ? 4 : components) * scalarBytes;
                bytes = components * scalarBytes;
            } else throw new IllegalArgumentException("Unsupported block field type: " + name);
            offset = align(offset, alignment);
            var writer = layout.fields().get(field.getName());
            if (writer == null) throw new IllegalArgumentException("No serializer for block field " + field.getName());
            fields.add(new Field<>(field.getName(), offset, writer));
            offset = Math.addExact(offset, bytes); blockAlignment = Math.max(blockAlignment, alignment);
        }
        return new LayoutSerializer<>(fields, align(offset, blockAlignment));
    }
    private static int align(int value, int alignment) { return Math.multiplyExact(Math.floorDiv(Math.addExact(value, alignment - 1), alignment), alignment); }
    @Override public void accept(T value, ByteBuffer buffer) {
        if (buffer.remaining() < this.size) throw new IllegalArgumentException("Destination smaller than native std140 layout");
        for (Field<T> field : this.fields) field.serializer.write(value, field.offset, buffer);
    }
    public record Field<T>(String name, int offset, VeilShaderBufferLayout.FieldSerializer<T> serializer) { }
}

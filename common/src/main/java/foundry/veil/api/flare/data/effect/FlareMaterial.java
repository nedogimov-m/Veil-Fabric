package foundry.veil.api.flare.data.effect;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import foundry.veil.api.client.property.InapplicableProperty;
import foundry.veil.api.client.property.InvertibleProperty;
import foundry.veil.api.client.property.ModelProperty;
import foundry.veil.api.client.property.Property;
import foundry.veil.api.client.property.properties.FloatProperty;
import foundry.veil.api.client.property.properties.RandomFloatProperty;
import foundry.veil.api.client.registry.PropertyRegistry;
import foundry.veil.api.flare.EffectHost;
import foundry.veil.api.flare.modifier.PropertyModifier;
import foundry.veil.api.util.CodecUtil;
import it.unimi.dsi.fastutil.objects.Object2ObjectArrayMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import foundry.veil.api.client.render.gpu.GpuShaderProgram;
import foundry.veil.api.client.render.gpu.GpuUniformBuffer;
import foundry.veil.api.client.property.properties.Sampler2DProperty;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;

/**
 * @since 2.5.0
 */
public final class FlareMaterial {
    public static final String UNIFORM_BLOCK = "FlareMaterial";

    public static Codec<FlareMaterial> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.fieldOf("class").forGetter(FlareMaterial::clazz),
            Identifier.CODEC.fieldOf("renderType").forGetter(FlareMaterial::renderTypeLocation),
            Codec.BOOL.optionalFieldOf("randomizeSeed", false).forGetter(FlareMaterial::randomizeSeed),
            Codec.unboundedMap(
                            Codec.STRING,
                            CodecUtil.registryOrLegacyCodec(PropertyRegistry.REGISTRY)
                                    .<Property<?>>dispatchMap(Property::getType, Property::codec)
                                    .codec()
                    )
                    .optionalFieldOf("properties", Map.of())
                    .forGetter(FlareMaterial::properties)
    ).apply(instance, (clazz, renderType, randomizeSeed, properties) -> new FlareMaterial(clazz, renderType, randomizeSeed, new Object2ObjectArrayMap<>(properties))));

    private final String clazz;
    private final Identifier renderTypeLocation;
    private final boolean randomizeSeed;
    private final Object2ObjectArrayMap<String, Property<?>> properties;
    private GpuUniformBuffer.Layout uniformLayout;

    public FlareMaterial(String clazz, Identifier renderTypeLocation, boolean randomizeSeed, Map<String, Property<?>> properties) {
        properties = new Object2ObjectArrayMap<>(properties);
        properties.put("_ClipBrightness", new FloatProperty(1.0f));
        if (randomizeSeed) {
            properties.put("_Seed", RandomFloatProperty.INSTANCE);
        }
        this.clazz = clazz;
        this.renderTypeLocation = renderTypeLocation;
        this.randomizeSeed = randomizeSeed;
        this.properties = new Object2ObjectArrayMap<>(properties);
        this.uniformLayout = createLayout(this.properties);
    }

    private static GpuUniformBuffer.Layout createLayout(Map<String, Property<?>> properties) {
        var fields = new java.util.TreeMap<String, GpuUniformBuffer.ValueType>();
        properties.forEach((name, property) -> {
            if (property.getClass().getAnnotation(InapplicableProperty.class) != null || property instanceof Sampler2DProperty) return;
            var type = GpuUniformBuffer.ValueType.fromGlsl(property.getType().glType().getName());
            if (fields.putIfAbsent(name, type) != null) throw new IllegalArgumentException("Duplicate material uniform field: " + name);
            if (property instanceof InvertibleProperty<?> && fields.putIfAbsent("I" + name, type) != null) {
                throw new IllegalArgumentException("Material inverse field conflicts with an existing property: I" + name);
            }
        });
        var layout = GpuUniformBuffer.layoutBuilder();
        fields.forEach(layout::field);
        return layout.build();
    }

    /** Attach shared model values during effect construction, before capturing native material layouts. */
    public void addProperties(Map<String, Property<?>> properties) {
        this.properties.putAll(properties);
        this.uniformLayout = createLayout(this.properties);
    }

    public void applyProperties(EffectHost host, @Nullable GpuShaderProgram shader, Map<String, List<PropertyModifier<?>>> modifiers) {
        String block = shader != null && shader.hasUniform(UNIFORM_BLOCK) ? UNIFORM_BLOCK
                : foundry.veil.api.client.render.gpu.GpuShaderSource.MANAGED_UNIFORMS;
        this.applyProperties(host, shader, block, modifiers);
    }
    
    public void applyProperties(EffectHost host, @Nullable GpuShaderProgram shader, String block, Map<String, List<PropertyModifier<?>>> modifiers) {
        if (shader == null || this.properties.isEmpty()) {
            return;
        }
        if (shader.hasUniform(block)) {
            GpuUniformBuffer uniforms = shader.getUniformBlock(block);
            if (uniforms == null) shader.createUniformBlock(block, this.uniformLayout);
            else if (!block.equals(foundry.veil.api.client.render.gpu.GpuShaderSource.MANAGED_UNIFORMS)
                    && !uniforms.layout().equals(this.uniformLayout)) throw new IllegalArgumentException("Material properties do not match native shader block " + block);
        }
        for (Object2ObjectMap.Entry<String, Property<?>> entry : this.properties.object2ObjectEntrySet()) {
            Property<?> property = entry.getValue();
            Class<?> propertyClass = property.getClass();
            
            if (propertyClass.getAnnotation(InapplicableProperty.class) != null) {
                continue;
            }
            
            String name = entry.getKey();
            if (propertyClass.getAnnotation(ModelProperty.class) == null) {
                PropertyModifier.modifyProperty(host, this.clazz, property, modifiers.get(name));
            }
            
            property.applyValue(name, shader, block);
            if (property instanceof InvertibleProperty<?> invertibleProperty) {
                invertibleProperty.applyInverseValue(name, shader, block);
            }
        }
    }
    
    public void resetProperties(EffectHost host, @Nullable GpuShaderProgram shader) {
        if (shader == null) {
            return;
        }
        for (Object2ObjectMap.Entry<String, Property<?>> entry : this.properties.object2ObjectEntrySet()) {
            Property<?> property = entry.getValue();
            if (property.getClass().getAnnotation(ModelProperty.class) != null) {
                continue;
            }
            property.resetOverrideValue();
        }
    }
    
    public String clazz() {
        return this.clazz;
    }
    
    public Identifier renderTypeLocation() {
        return this.renderTypeLocation;
    }
    
    public boolean randomizeSeed() {
        return this.randomizeSeed;
    }

    public Map<String, Property<?>> properties() {
        return java.util.Collections.unmodifiableMap(this.properties);
    }

    public GpuUniformBuffer.Layout uniformLayout() { return this.uniformLayout; }
}

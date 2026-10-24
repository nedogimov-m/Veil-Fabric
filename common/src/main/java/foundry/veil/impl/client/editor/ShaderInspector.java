package foundry.veil.impl.client.editor;

import com.mojang.renderpearl.api.pipeline.*;
import foundry.veil.api.client.editor.SingleWindowInspector;
import foundry.veil.api.client.render.gpu.GpuShaderProgram;
import foundry.veil.api.client.render.gpu.GpuUniformBuffer;
import foundry.veil.api.client.render.shader.ShaderManager;
import foundry.veil.api.client.render.shader.ShaderPreDefinitions;
import foundry.veil.impl.client.render.shader.debug.NativePipelineRegistry;
import imgui.ImGui;
import imgui.ImGuiListClipper;
import imgui.callback.ImListClipperCallback;
import imgui.flag.ImGuiInputTextFlags;
import imgui.flag.ImGuiWindowFlags;
import imgui.type.ImBoolean;
import imgui.type.ImString;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import org.jetbrains.annotations.Nullable;
import java.util.*;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** Native live pipeline metadata and declared typed CPU uniforms. Inspection never compiles a shader. */
public final class ShaderInspector extends SingleWindowInspector implements ResourceManagerReloadListener {
    public static final Component TITLE = Component.translatable("inspector.veil.shader.title");
    private static @Nullable Runtime runtime;
    private final ImString filterText = new ImString(128);
    private final ImString definitionText = new ImString(128);
    private final ImBoolean infoVisible = new ImBoolean();
    private final ImBoolean sourceVisible = new ImBoolean();
    private @Nullable Pattern filter;
    private String filterError = "";
    private String namespace = "All";
    private List<NativePipelineRegistry.Entry> entries = List.of();
    private @Nullable NativePipelineRegistry.Entry selected;
    private ImString sourceText = new ImString();
    private String sourceTitle = "Shader source";

    public static void bindRuntime(ShaderManager shaders, ShaderPreDefinitions definitions, ResourceManager resources) {
        runtime = new Runtime(Objects.requireNonNull(shaders), Objects.requireNonNull(definitions), Objects.requireNonNull(resources));
    }
    public static void unbindRuntime() { runtime = null; }
    @Override public Component getDisplayName() { return TITLE; }
    @Override public Component getGroup() { return RENDERER_GROUP; }
    @Override public void onShow() { super.onShow(); this.refresh(); }
    private void refresh() {
        this.entries = NativePipelineRegistry.snapshot();
        if (this.selected != null && this.entries.stream().noneMatch(entry -> entry.definition() == this.selected.definition())) this.selected = null;
    }
    @Override protected void renderComponents() {
        if (ImGui.button("Refresh")) this.refresh();
        ImGui.sameLine(); ImGui.text(this.entries.size() + " live compiled pipelines");
        if (ImGui.beginCombo("Namespace", this.namespace)) {
            Set<String> namespaces = new TreeSet<>(); namespaces.add("All");
            this.entries.forEach(entry -> namespaces.add(entry.definition().getLocation().getNamespace()));
            for (String name : namespaces) if (ImGui.selectable(name, this.namespace.equals(name))) this.namespace = name;
            ImGui.endCombo();
        }
        if (ImGui.inputTextWithHint("##pipeline_filter", "Regular expression", this.filterText)) {
            this.filter = null; this.filterError = "";
            if (!this.filterText.isEmpty()) try { this.filter = Pattern.compile(this.filterText.get()); }
            catch (PatternSyntaxException failure) { this.filterError = failure.getDescription(); }
        }
        if (!this.filterError.isEmpty()) ImGui.textWrapped(this.filterError);
        if (ImGui.beginChild("##native_pipelines", ImGui.getContentRegionAvailX() * .6f, 0)) {
            if (ImGui.beginListBox("##pipelines", -Float.MIN_VALUE, -Float.MIN_VALUE)) {
                for (var entry : this.entries) {
                    String name = entry.definition().getLocation().toString();
                    if (!this.namespace.equals("All") && !entry.definition().getLocation().getNamespace().equals(this.namespace)) continue;
                    if (this.filter != null && !this.filter.matcher(name).find()) continue;
                    String label = name + "##" + System.identityHashCode(entry.definition());
                    if (ImGui.selectable(label, this.selected != null && entry.definition() == this.selected.definition())) this.selected = entry;
                    if (ImGui.isItemHovered()) ImGui.setTooltip(entry.definition().getPrimitiveTopology() + ", " + entry.definition().getColorTargetStates().size() + " color targets");
                }
                ImGui.endListBox();
            }
        }
        ImGui.endChild(); ImGui.sameLine();
        if (ImGui.beginChild("##native_pipeline_controls", 0, 0)) {
            if (this.selected != null && this.selected.isLive()) {
                ImGui.textWrapped(this.selected.definition().getLocation().toString());
                for (var shader : this.selected.definition().getShaders().entrySet()) {
                    if (ImGui.button("Open " + shader.getKey().name().toLowerCase(Locale.ROOT) + " source")) this.openSource(shader.getKey(), shader.getValue());
                }
                if (ImGui.button("Pipeline and uniforms")) this.infoVisible.set(true);
            }
            this.renderDefinitions();
        }
        ImGui.endChild();
    }
    private void renderDefinitions() {
        Runtime owner = runtime;
        if (owner == null) return;
        ImGui.separator(); ImGui.text("Shader definitions");
        if (ImGui.inputTextWithHint("##native_definition", "NAME=value", this.definitionText, ImGuiInputTextFlags.EnterReturnsTrue)) {
            String[] parts = this.definitionText.get().split("=", 2); String name = parts[0].trim();
            if (name.matches("[A-Za-z_][A-Za-z0-9_]*")) { owner.definitions.set(name, parts.length == 1 ? null : parts[1].trim()); this.definitionText.clear(); }
        }
        List<String> remove = new ArrayList<>();
        for (var definition : owner.definitions.getDefinitions().entrySet()) {
            ImGui.pushID(definition.getKey()); ImGui.textWrapped(definition.getKey() + "=" + definition.getValue()); ImGui.sameLine();
            if (ImGui.smallButton("X")) remove.add(definition.getKey());
            ImGui.popID();
        }
        remove.forEach(owner.definitions::remove);
    }
    @Override public void render() {
        ImGui.setNextWindowSizeConstraints(600, 400, Float.MAX_VALUE, Float.MAX_VALUE); super.render();
        if (this.infoVisible.get()) {
            if (ImGui.begin("Native pipeline and uniforms", this.infoVisible, ImGuiWindowFlags.NoSavedSettings)) this.renderInfo();
            ImGui.end();
        }
        if (this.sourceVisible.get()) {
            if (ImGui.begin(this.sourceTitle, this.sourceVisible, ImGuiWindowFlags.NoSavedSettings)) {
                if (ImGui.button("Copy source")) ImGui.setClipboardText(this.sourceText.get());
                ImGui.textWrapped("Resource source. Native preprocessing and includes are applied when the pipeline compiles.");
                ImGui.inputTextMultiline("##native_shader_source", this.sourceText, -Float.MIN_VALUE, -Float.MIN_VALUE, ImGuiInputTextFlags.ReadOnly);
            }
            ImGui.end();
        }
    }
    private @Nullable GpuShaderProgram selectedProgram() {
        Runtime owner = runtime;
        if (owner == null || this.selected == null) return null;
        return owner.shaders.findShader(this.selected.definition());
    }
    private void renderInfo() {
        if (this.selected == null || !this.selected.isLive()) { ImGui.text("Pipeline has retired. Refresh the list."); return; }
        RenderPipeline pipeline = this.selected.definition();
        ImGui.textWrapped(pipeline.getLocation().toString());
        ImGui.text("Topology: " + pipeline.getPrimitiveTopology() + "; polygon: " + pipeline.getPolygonMode() + "; cull: " + pipeline.isCull());
        ImGui.textWrapped("Depth: " + pipeline.getDepthStencilState() + "; push constants: " + pipeline.pushConstantSize() + " bytes");
        for (int index = 0; index < pipeline.getColorTargetStates().size(); index++) ImGui.textWrapped("Color " + index + ": " + pipeline.getColorTargetStates().get(index));
        if (ImGui.collapsingHeader("Vertex layouts")) {
            for (int slot = 0; slot < pipeline.getVertexFormatBindings().size(); slot++) {
                var format = pipeline.getVertexFormatBinding(slot); if (format == null) continue;
                ImGui.text("Binding " + slot + ": stride " + format.getVertexSize() + ", instance step " + format.getStepRate());
                for (var field : format.getElements()) ImGui.text("  " + field.name() + ": " + field.format() + " at " + field.offset());
            }
        }
        if (ImGui.collapsingHeader("Shader sources and macros")) {
            pipeline.getShaders().forEach((stage, id) -> ImGui.text(stage + ": " + id));
            pipeline.getShaderDefines().values().forEach((name, value) -> ImGui.text(name + "=" + value));
            pipeline.getShaderDefines().flags().forEach(ImGui::text);
        }
        GpuShaderProgram program = this.selectedProgram();
        if (ImGui.collapsingHeader("Uniform bindings")) {
            for (var uniform : BindGroupLayout.flattenUniforms(pipeline.getBindGroupLayouts())) {
                ImGui.pushID(uniform.name());
                if (ImGui.collapsingHeader(uniform.name() + ": " + uniform.type())) {
                    if (program == null) ImGui.textWrapped("Native declaration. The draw owner has not supplied a CPU schema or current binding values.");
                    else this.renderBinding(program, uniform.name(), uniform.type());
                }
                ImGui.popID();
            }
        }
        if (program != null && ImGui.collapsingHeader("Resource dependencies")) {
            program.includedResources().stream().sorted(Comparator.comparing(Identifier::toString)).forEach(id -> ImGui.text("include " + id));
            program.getDefinitionDependencies().stream().sorted().forEach(name -> ImGui.text("definition " + name));
        }
    }
    private void renderBinding(GpuShaderProgram program, String name, UniformType type) {
        if (type == UniformType.COMBINED_IMAGE_SAMPLER) {
            var binding = program.snapshotTextures().get(name);
            if (binding == null) { ImGui.text("No current texture binding"); return; }
            if (binding.view().isClosed() || binding.sampler().isClosed()) { ImGui.text("Texture/sampler retired"); return; }
            ImGui.text(binding.view().getWidth(0) + " x " + binding.view().getHeight(0) + " " + binding.view().texture().getFormat());
            ImGui.text("Mip " + binding.view().baseMipLevel() + ", levels " + binding.view().mipLevels());
            ImGui.text("Min " + binding.sampler().getMinFilter() + ", mag " + binding.sampler().getMagFilter());
            ImGui.text("Address " + binding.sampler().getAddressModeU() + ", " + binding.sampler().getAddressModeV());
        } else if (type == UniformType.UNIFORM_BUFFER) {
            GpuUniformBuffer block = program.getUniformBlock(name);
            if (block == null) {
                Long bytes = program.uniformBlockSizes().get(name);
                ImGui.textWrapped(bytes == null ? "External buffer not currently bound; CPU schema unknown" : "Borrowed native range: " + bytes + " bytes; CPU schema unknown");
                return;
            }
            ImGui.text(block.layout().size() + " bytes, std140. Values show the current CPU block.");
            for (var field : block.layout().fields().values()) {
                ImGui.text(field.type().glsl() + " " + field.name() + "; offset " + field.baseOffset() + ", stride " + field.stride());
                int count = Math.max(1, field.arrayLength());
                ImGuiListClipper.forEach(count, (int) ImGui.getTextLineHeightWithSpacing(), new ImListClipperCallback() {
                    @Override public void accept(int element) { ImGui.text((field.arrayLength() > 0 ? "[" + element + "] " : "") + formatField(block, field, element)); }
                });
            }
        } else ImGui.textWrapped("Typed native texel buffer; its owner must supply a schema for value inspection.");
    }
    public static String formatField(GpuUniformBuffer block, GpuUniformBuffer.Field field, int element) {
        if (!field.type().integer()) return Arrays.toString(block.getFloats(field.name(), element));
        int[] values = block.getInts(field.name(), element);
        if (!field.type().unsigned()) return Arrays.toString(values);
        long[] unsigned = new long[values.length]; for (int index = 0; index < values.length; index++) unsigned[index] = Integer.toUnsignedLong(values[index]);
        return Arrays.toString(unsigned);
    }
    private void openSource(ShaderType stage, Identifier id) {
        Runtime owner = runtime;
        if (owner == null) { this.sourceText = new ImString("Resource manager has not been bound"); }
        else try {
            Identifier file = stage.idConverter().idToFile(id);
            if (this.selectedProgram() != null) {
                Identifier custom = Identifier.fromNamespaceAndPath(id.getNamespace(), owner.shaders.getSourceSet().getFolder() + "/" + id.getPath() + (stage == ShaderType.VERTEX ? ".vsh" : ".fsh"));
                if (owner.resources.getResource(custom).isPresent()) file = custom;
            }
            this.sourceText = new ImString(owner.resources.getResourceOrThrow(file).readAllAsString());
        } catch (Exception failure) { this.sourceText = new ImString("Cannot read resource: " + failure.getMessage()); }
        this.sourceTitle = stage + " " + id + "###native_shader_source"; this.sourceVisible.set(true);
    }
    @Override public void onResourceManagerReload(ResourceManager resources) { this.refresh(); }
    @Override public void free() { this.entries = List.of(); this.selected = null; this.sourceText.clear(); }
    private record Runtime(ShaderManager shaders, ShaderPreDefinitions definitions, ResourceManager resources) { }
}

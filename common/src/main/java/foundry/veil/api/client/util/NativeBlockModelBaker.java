package foundry.veil.api.client.util;

import foundry.veil.ext.TextureAtlasExtension;
import net.fabricmc.fabric.api.client.model.loading.v1.SimpleUnbakedExtraModel;
import net.fabricmc.fabric.api.client.model.loading.v1.UnbakedModelDeserializer;
import net.minecraft.client.renderer.block.dispatch.BlockModelRotation;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.texture.SpriteLoader;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.resources.model.*;
import net.minecraft.client.resources.model.cuboid.*;
import net.minecraft.client.resources.model.geometry.*;
import net.minecraft.client.resources.model.sprite.*;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.data.AtlasIds;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import java.io.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Editor baking through the real 26.3 inherited geometry/material pipeline and resident native atlases. */
public final class NativeBlockModelBaker {
    private static final FileToIdConverter MODELS=FileToIdConverter.json("models");
    private NativeBlockModelBaker(){}
    public static ItemQuads bake(Identifier id,Reader editedRoot,ResourceManager resources,AtlasManager atlases){
        final var materials=new MaterialBaker(snapshot(atlases.getAtlasOrThrow(AtlasIds.BLOCKS)),snapshot(atlases.getAtlasOrThrow(AtlasIds.ITEMS)));
        final var baker=new Baker(id,CuboidModel.fromStream(editedRoot),resources,materials);
        final var resolved=baker.getModel(id);
        final var quads=resolved.bakeTopGeometry(resolved.getTopTextureSlots(),baker,BlockModelRotation.IDENTITY);
        materials.logMissingTextures();return ItemQuads.split(quads.getAll());
    }
    /** Bake one concrete resource model against resident atlases, including its inherited parents.
     * Fabric enforces the block-atlas contract; unrelated item models and templates are never roots.
     */
    public static BlockStateModel bakeBlockStateModel(Identifier id,ResourceManager resources,AtlasManager atlases){
        try(var reader=resources.openAsReader(MODELS.idToFile(id))){
            final var materials=new MaterialBaker(snapshot(atlases.getAtlasOrThrow(AtlasIds.BLOCKS)),snapshot(atlases.getAtlasOrThrow(AtlasIds.ITEMS)));
            final var baker=new Baker(id,UnbakedModelDeserializer.deserialize(reader),resources,materials,true);
            final var model=SimpleUnbakedExtraModel.blockStateModel(id).bake(baker);
            materials.logMissingTextures();return model;
        }catch(IOException failure){throw new UncheckedIOException("Cannot load block model resource "+id,failure);}
    }
    private static SpriteLoader.Preparations snapshot(TextureAtlas atlas){
        final var texture=atlas.getTexture();return new SpriteLoader.Preparations(texture.getWidth(0),texture.getHeight(0),texture.getMipLevels()-1,
                atlas.missingSprite(),((TextureAtlasExtension)atlas).veil$sprites(),CompletableFuture.completedFuture(null));
    }
    private record Resolved(Identifier id,UnbakedModel wrapped,@Nullable ResolvedModel parent) implements ResolvedModel{
        @Override public String debugName(){return id.toString();}
    }
    private static final class Baker implements ModelBaker {
        private final Identifier rootId;private final UnbakedModel root;private final ResourceManager resources;private final MaterialBaker materials;
        private final boolean nativeResourceModels;
        private final Map<Identifier,ResolvedModel> models=new HashMap<>();private final Set<Identifier> resolving=new HashSet<>();
        private final Map<SharedOperationKey<?>,Object> operations=new HashMap<>();
        private final Map<Vector3fc,Vector3fc> vectors=new HashMap<>();private final Map<BakedQuad.MaterialInfo,BakedQuad.MaterialInfo> materialInfos=new HashMap<>();
        private final Interner interner=new Interner(){
            public Vector3fc vector(Vector3fc value){final var copy=new Vector3f(value);return vectors.computeIfAbsent(copy,ignored->copy);}
            public BakedQuad.MaterialInfo materialInfo(BakedQuad.MaterialInfo value){return materialInfos.computeIfAbsent(value,ignored->value);}
        };
        private final ModelBakery.MissingModels missing;
        Baker(Identifier id,UnbakedModel root,ResourceManager resources,MaterialBaker materials){
            this(id,root,resources,materials,false);
        }
        Baker(Identifier id,UnbakedModel root,ResourceManager resources,MaterialBaker materials,boolean nativeResourceModels){
            this.rootId=id;this.root=root;this.resources=resources;this.materials=materials;
            this.nativeResourceModels=nativeResourceModels;
            final var model=new Resolved(MissingCuboidModel.LOCATION,MissingCuboidModel.missingModel(),null);
            models.put(MissingCuboidModel.LOCATION,model);missing=ModelBakery.MissingModels.bake(model,materials,interner);
        }
        @Override public ResolvedModel getModel(Identifier id){
            final var existing=models.get(id);if(existing!=null)return existing;
            if(!resolving.add(id))throw new IllegalArgumentException("Cyclic editor block model parent: "+id);
            try{
                final UnbakedModel data;
                if(id.equals(rootId))data=root;
                else try(var reader=resources.openAsReader(MODELS.idToFile(id))){data=nativeResourceModels?UnbakedModelDeserializer.deserialize(reader):CuboidModel.fromStream(reader);}
                final var model=new Resolved(id,data,data.parent()!=null?getModel(data.parent()):null);models.put(id,model);return model;
            }catch(IOException failure){throw new UncheckedIOException("Cannot resolve editor block model parent "+id,failure);}
            finally{resolving.remove(id);}
        }
        @Override public BlockStateModelPart missingBlockModelPart(){return missing.blockPart();}
        @Override public MaterialBaker materials(){return materials;}
        @Override public Interner interner(){return interner;}
        @Override @SuppressWarnings("unchecked") public <T> T compute(SharedOperationKey<T> operation){return (T)operations.computeIfAbsent(operation,key->operation.compute(this));}
    }
}

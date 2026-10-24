package foundry.veil.api.client.render.texture;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import net.minecraft.server.packs.metadata.MetadataSectionType;
import java.util.Locale;

/** Native metadata codec retains the original veil:texture_type section and type strings. */
public record TextureTypeMetadataSection(TextureType type) {
    public enum TextureType {TEXTURE_2D,TEXTURE_2D_ARRAY,TEXTURE_CUBE_MAP;}
    public static final Codec<TextureTypeMetadataSection> CODEC=Codec.STRING.comapFlatMap(name->{
        for(var type:TextureType.values())if(type.name().substring(8).equalsIgnoreCase(name))return DataResult.success(type);
        return DataResult.error(()->"Unknown texture type: "+name+". Expected 2d, 2d_array or cube_map");
    },type->type.name().substring(8).toLowerCase(Locale.ROOT)).optionalFieldOf("type",TextureType.TEXTURE_2D)
            .xmap(TextureTypeMetadataSection::new,TextureTypeMetadataSection::type).codec();
    public static final MetadataSectionType<TextureTypeMetadataSection> TYPE=new MetadataSectionType<>("veil:texture_type",CODEC);
}

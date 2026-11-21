package studio.detemkur.illustratedbooks;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.*;
import net.minecraft.resources.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;

public final class IllustratedBooks implements ModInitializer {
    public static final String ID="illustratedbooks";
    public static final int PAGES=16;
    public static Item BOOK;
    public static Identifier id(String path) { return Identifier.fromNamespaceAndPath(ID,path); }
    @Override public void onInitialize() {
        var key=ResourceKey.create(Registries.ITEM,id("illustrated_book"));
        BOOK=Registry.register(BuiltInRegistries.ITEM,key,new BookItem(new Item.Properties().setId(key).stacksTo(1)));
        CreativeModeTabEvents.modifyOutputEvent(ResourceKey.create(Registries.CREATIVE_MODE_TAB,
            Identifier.fromNamespaceAndPath("minecraft","tools_and_utilities"))).register(entries -> entries.accept(BOOK));
        BookPackets.register();BookServer.register();
    }
    public static String picture(ItemStack stack,int page) {
        if (!stack.is(BOOK) || page<0 || page>=PAGES) return "";
        String value=stack.getOrDefault(DataComponents.CUSTOM_DATA,CustomData.EMPTY).copyTag().getStringOr("image_"+page,"");
        return PngFile.isId(value) ? value : "";
    }
    public static String text(ItemStack stack,int page) {
        if (!stack.is(BOOK) || page<0 || page>=PAGES) return "";
        return stack.getOrDefault(DataComponents.CUSTOM_DATA,CustomData.EMPTY).copyTag().getStringOr("text_"+page,"");
    }
    public static void page(ItemStack stack,int page,String image,String text) {
        var data=stack.getOrDefault(DataComponents.CUSTOM_DATA,CustomData.EMPTY).copyTag();
        if (image!=null) {
            if (image.isEmpty()) data.remove("image_"+page);else data.putString("image_"+page,image);
        }
        if (text!=null) data.putString("text_"+page,text);
        stack.set(DataComponents.CUSTOM_DATA,CustomData.of(data));
    }
    private static final class BookItem extends Item {
        BookItem(Properties properties) { super(properties); }
        @Override public InteractionResult use(Level level,Player player,InteractionHand hand) {
            if (player instanceof ServerPlayer server && ServerPlayNetworking.canSend(server,BookPackets.Open.TYPE)) {
                int slot=hand==InteractionHand.OFF_HAND ? 40 : player.getInventory().getSelectedSlot();
                ServerPlayNetworking.send(server,new BookPackets.Open(slot));
            }
            return InteractionResult.SUCCESS;
        }
    }
}

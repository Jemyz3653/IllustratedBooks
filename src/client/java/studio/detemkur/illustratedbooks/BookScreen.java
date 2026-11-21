package studio.detemkur.illustratedbooks;

import java.io.IOException;
import java.nio.file.*;
import java.util.List;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

final class BookScreen extends Screen {
    private final int slot;
    private final String[] drafts=new String[IllustratedBooks.PAGES];
    private final String[] saved=new String[IllustratedBooks.PAGES];
    private int page,ticks;
    private EditBox path;
    private MultiLineEditBox text;
    private Button previous,next,upload,clear;
    private int left,top,panelWidth,panelHeight,imageHeight;
    private Component status=Component.empty();
    BookScreen(int slot) {
        super(Component.translatable("item.illustratedbooks.illustrated_book"));this.slot=slot;
        for (int i=0;i<drafts.length;i++) drafts[i]=saved[i]=IllustratedBooks.text(book(),i);
    }
    private ItemStack book() {
        var player=Minecraft.getInstance().player;
        if (player==null || slot<0 || slot>=player.getInventory().getContainerSize()) return ItemStack.EMPTY;
        return player.getInventory().getItem(slot);
    }
    @Override protected void init() {
        String oldPath=path==null ? "" : path.getValue();
        if (text!=null) drafts[page]=text.getValue();
        panelWidth=Math.min(360,width-12);panelHeight=Math.min(300,height-12);
        left=(width-panelWidth)/2;top=(height-panelHeight)/2;
        imageHeight=Math.max(36,panelHeight-180);
        int contentWidth=panelWidth-24;
        int y=top+28+imageHeight+8;
        text=MultiLineEditBox.builder().setX(left+12).setY(y).setTextColor(0xff30251c)
            .setShowBackground(true).build(font,contentWidth,44,Component.translatable("illustratedbooks.text"));
        text.setCharacterLimit(2048);text.setValue(drafts[page]);addRenderableWidget(text);
        path=new EditBox(font,left+12,y+50,contentWidth,18,Component.translatable("illustratedbooks.path"));
        path.setMaxLength(4096);path.setHint(Component.translatable("illustratedbooks.path"));path.setValue(oldPath);addRenderableWidget(path);
        upload=addRenderableWidget(Button.builder(Component.translatable("illustratedbooks.load"),button -> loadPath())
            .bounds(left+12,y+73,contentWidth/2-3,18).build());
        clear=addRenderableWidget(Button.builder(Component.translatable("illustratedbooks.clear"),button -> upload(null))
            .bounds(left+15+contentWidth/2,y+73,contentWidth/2-3,18).build());
        previous=addRenderableWidget(Button.builder(Component.literal("<"),button -> turn(-1)).bounds(left+12,y+97,35,18).build());
        next=addRenderableWidget(Button.builder(Component.literal(">"),button -> turn(1)).bounds(left+52,y+97,35,18).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.done"),button -> onClose()).bounds(left+panelWidth-90,y+97,78,18).build());
    }
    private void turn(int change) { saveText();page+=change;text.setValue(drafts[page]);status=Component.empty(); }
    private void saveText() {
        if (text==null) return;
        drafts[page]=text.getValue();
        if (!drafts[page].equals(saved[page]) && ClientPlayNetworking.canSend(BookPackets.Text.TYPE)) {
            ClientPlayNetworking.send(new BookPackets.Text(slot,page,drafts[page]));saved[page]=drafts[page];
        }
    }
    @Override public void tick() { if (++ticks%20==0) saveText(); }
    @Override public void onClose() { saveText();super.onClose(); }
    private void loadPath() {
        try { upload(Path.of(path.getValue().trim())); }
        catch (InvalidPathException error) { setStatus(Component.translatable("illustratedbooks.bad_png")); }
    }
    private void upload(Path file) {
        try {
            if (file!=null) PngFile.header(file);
            IllustratedBooksClient.start(this,slot,page,file);
            status=Component.translatable("illustratedbooks.loading");
        } catch (IOException|IllegalStateException error) { setStatus(Component.translatable("illustratedbooks.bad_png")); }
    }
    @Override public void onFilesDrop(List<Path> files) {
        if (files.size()==1) { path.setValue(files.getFirst().toString());upload(files.getFirst()); }
        else setStatus(Component.translatable("illustratedbooks.one_png"));
    }
    void setStatus(Component text) { status=text; }
    void clearPreview() { status=Component.translatable("illustratedbooks.cleared"); }
    @Override public void extractRenderState(GuiGraphicsExtractor graphics,int mouseX,int mouseY,float delta) {
        graphics.fill(left,top,left+panelWidth,top+panelHeight,0xffb99567);
        graphics.fill(left+2,top+2,left+panelWidth-2,top+panelHeight-2,0xfff0e6c8);
        graphics.centeredText(font,Component.translatable("illustratedbooks.page",page+1,IllustratedBooks.PAGES),width/2,top+10,0xff403321);
        String id=IllustratedBooks.picture(book(),page);
        var image=BookTextures.get(id);
        if (image!=null) {
            float scale=Math.min((panelWidth-28F)/image.width(),(imageHeight-4F)/image.height());
            int w=Math.max(1,Math.round(image.width()*scale)),h=Math.max(1,Math.round(image.height()*scale));
            int x=width/2-w/2,y=top+28+(imageHeight-h)/2;
            graphics.blit(image.name(),x,y,x+w,y+h,0F,1F,0F,1F);
        } else {
            if (!id.isEmpty()) BookTextures.request(slot,id);
            graphics.centeredText(font,Component.translatable("illustratedbooks.drop"),width/2,top+28+imageHeight/2,0xff75624a);
        }
        boolean busy=IllustratedBooksClient.busy();
        previous.active=page>0 && !busy;next.active=page<IllustratedBooks.PAGES-1 && !busy;
        upload.active=!busy;clear.active=!busy && !id.isEmpty();
        int progress=IllustratedBooksClient.progress(this);
        graphics.centeredText(font,progress<0 ? status : Component.translatable("illustratedbooks.progress",progress),width/2,top+panelHeight-12,0xff694e29);
        super.extractRenderState(graphics,mouseX,mouseY,delta);
    }
    @Override public boolean isPauseScreen() { return false; }
}

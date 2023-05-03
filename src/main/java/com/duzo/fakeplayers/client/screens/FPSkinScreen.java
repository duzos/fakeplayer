package com.duzo.fakeplayers.client.screens;

import com.duzo.fakeplayers.FakePlayers;
import com.duzo.fakeplayers.common.entities.HumanoidEntity;
import com.duzo.fakeplayers.common.entities.humanoids.FakePlayerEntity;
import com.duzo.fakeplayers.networking.Network;
import com.duzo.fakeplayers.networking.packets.SendHumanoidChatC2SPacket;
import com.duzo.fakeplayers.networking.packets.UpdateHumanoidAIC2SPacket;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
public class FPSkinScreen extends Screen {
    private static final ResourceLocation GUI_TEXTURE = new ResourceLocation(FakePlayers.MODID,"textures/gui/skin_select.png");
    private FakePlayerEntity humanoid;
    private Player player;
    protected int imageWidth = 176;
    protected int imageHeight = 166;
    private EditBox input,chatBox;
    private Button confirm,send, stayPut, follow, wander;
    public FPSkinScreen(Component component, FakePlayerEntity humanoid, Player player) {
        super(component);
        this.humanoid = humanoid;
        this.player = player;

        if (this.minecraft == null) {
            this.minecraft = this.getMinecraft();
        }
    }

    @Override
    protected void init() {
        super.init();
        int l = this.height / 4 + 48;
        int i = (this.width - this.imageWidth) / 2;
        int j = (this.height - this.imageWidth) / 2;

        assert this.minecraft != null;
        this.input = new EditBox(this.minecraft.fontFilterFishy, (i) + (this.imageWidth/2)  - j + (j/2),l,j, 12, Component.translatable("screen.fakeplayers.skin"));
        this.input.setValue(this.humanoid.getURL());
        this.input.setMaxLength(100);
        this.input.setBordered(true);
        this.addWidget(this.input);

        this.chatBox = new EditBox(this.minecraft.fontFilterFishy, (i) + (this.imageWidth/2)  - j + (j/2),l + 20,j, 12, Component.translatable("screen.fakeplayers.chatbox"));
        this.chatBox.setValue("Input chat message here!");
        this.chatBox.setEditable(true);
        this.chatBox.setMaxLength(100);
        this.chatBox.setBordered(true);
        this.addWidget(this.chatBox);

        this.send = new Button.Builder(Component.translatable("screens.fakeplayers.send"), (p_96786_) -> {
            this.pressSendChatButton();
        }).bounds((i) + (this.imageWidth/2) - (98/2),l + 40,98,20).build();
        this.addRenderableWidget(this.send);

        this.confirm = new Button.Builder(Component.translatable("screens.fakeplayers.done"), (p_96786_) -> {
            this.pressDoneButton();
        }).bounds((i) + (this.imageWidth/2) - (98/2),l + (128),98,20).build();
        this.addRenderableWidget(this.confirm);


        this.stayPut = new Button.Builder(Component.translatable("screens.fakeplayers.stayPut"), (p_96786_) -> {
            this.pressStayPut();
        }).bounds((i) + (this.imageWidth/2) - 23, l + 100,46,20).build();
        this.addRenderableWidget(this.stayPut);
        this.wander = new Button.Builder(Component.translatable("screens.fakeplayers.wander"), (p_96786_) -> {
            this.pressWanderButton();
        }).bounds((i) + (this.imageWidth/2) - (23*3), l + 100,46,20).build();
        this.addRenderableWidget(this.wander);
        this.follow = new Button.Builder(Component.translatable("screens.fakeplayers.follow"), (p_96786_) -> {
            this.pressFollowButton();
        }).bounds((i) + (this.imageWidth/2) + 23, l + 100,46,20).build();
        this.addRenderableWidget(this.follow);
    }


    private void updateEntityURL() {
        this.humanoid.setURL(this.input.getValue());
    }

    private void pressDoneButton() {
        this.updateEntityURL();
        this.humanoid.updateSkin();
        this.onClose();
    }

    private void pressSendChatButton() {
        Network.sendToServer(new SendHumanoidChatC2SPacket(this.humanoid.getUUID(), this.chatBox.getValue()));
    }

    private void pressStayPut() {
        this.stayPut.active = false;
        this.follow.active = true;
        this.wander.active = true;

        this.humanoid.setNoAi(true);
        this.humanoid.setForceTargeting(false);

        Network.sendToServer(new UpdateHumanoidAIC2SPacket(this.humanoid.getUUID(),true,false,false));
    }
    private void pressWanderButton() {
        this.stayPut.active = true;
        this.follow.active = true;
        this.wander.active = false;

        this.humanoid.setNoAi(false);
        this.humanoid.setForceTargeting(false);

        Network.sendToServer(new UpdateHumanoidAIC2SPacket(this.humanoid.getUUID(),false,false,false));
    }
    private void pressFollowButton() {
        this.stayPut.active = true;
        this.follow.active = false;
        this.wander.active = true;

        this.humanoid.setNoAi(false);
        this.humanoid.setForceTargeting(this.player);

        Network.sendToServer(new UpdateHumanoidAIC2SPacket(this.humanoid.getUUID(),false,true,true));
    }

    @Override
    public void render(PoseStack pPoseStack, int mouseX, int mouseY, float delta) {
        this.renderBg(pPoseStack);
        super.render(pPoseStack, mouseX, mouseY, delta);
        if (this.input != null && this.confirm != null) {
            this.input.render(pPoseStack, mouseX, mouseY, delta);
            this.chatBox.render(pPoseStack, mouseX, mouseY, delta);
        }
    }

    public void renderBg(PoseStack p_96557_) {
        RenderSystem.setShaderTexture(0, GUI_TEXTURE);
        int i = (this.width - this.imageWidth) / 2;
        int j = (this.height - this.imageHeight) / 2;
        blit(p_96557_, i, j, 0, 0, this.imageWidth, this.imageHeight);
    }
}

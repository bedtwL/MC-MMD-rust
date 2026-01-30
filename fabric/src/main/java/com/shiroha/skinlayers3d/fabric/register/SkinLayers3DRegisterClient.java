package com.shiroha.skinlayers3d.fabric.register;

import com.shiroha.skinlayers3d.fabric.config.ModConfigScreen;
import com.shiroha.skinlayers3d.fabric.network.SkinLayers3DNetworkPack;
import com.shiroha.skinlayers3d.maid.MaidActionNetworkHandler;
import com.shiroha.skinlayers3d.maid.MaidModelNetworkHandler;
import com.shiroha.skinlayers3d.renderer.render.SkinLayersRenderFactory;
import com.shiroha.skinlayers3d.ui.ActionWheelNetworkHandler;
import com.shiroha.skinlayers3d.ui.ConfigWheelScreen;
import com.shiroha.skinlayers3d.ui.MaidConfigWheelScreen;
import com.mojang.blaze3d.platform.InputConstants;

import java.io.File;
import java.util.UUID;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.glfw.GLFW;

/**
 * Fabric 客户端注册
 * 负责按键绑定、网络通信和实体渲染器注册
 */
@Environment(EnvType.CLIENT)
public class SkinLayers3DRegisterClient {
    static final Logger logger = LogManager.getLogger();
    
    // 主配置轮盘按键 (Alt，可自定义)
    static KeyMapping keyConfigWheel = new KeyMapping("key.skinlayers3d.config_wheel", 
        InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_LEFT_ALT, "key.categories.skinlayers3d");
    
    // 女仆配置轮盘按键 (B，对着女仆时生效)
    static KeyMapping keyMaidConfigWheel = new KeyMapping("key.skinlayers3d.maid_config_wheel", 
        InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_B, "key.categories.skinlayers3d");
    
    // 追踪按键状态
    private static boolean configWheelKeyWasDown = false;
    private static boolean maidConfigWheelKeyWasDown = false;

    public static void Register() {
        Minecraft MCinstance = Minecraft.getInstance();
        
        // 注册按键（仅两个）
        KeyBindingHelper.registerKeyBinding(keyConfigWheel);
        KeyBindingHelper.registerKeyBinding(keyMaidConfigWheel);
        
        // 设置模组设置界面工厂
        ConfigWheelScreen.setModSettingsScreenFactory(() -> ModConfigScreen.create(null));
        
        // 注册动作轮盘网络发送器
        ActionWheelNetworkHandler.setNetworkSender(animId -> {
            LocalPlayer player = MCinstance.player;
            if (player != null) {
                // 使用字符串传输动画ID，支持中文文件名
                logger.info("发送动作到服务器: " + animId);
                SkinLayers3DNetworkPack.sendToServer(1, player.getUUID(), animId);
            }
        });
        
        // 注册模型选择网络发送器
        com.shiroha.skinlayers3d.ui.ModelSelectorNetworkHandler.setNetworkSender(modelName -> {
            LocalPlayer player = MCinstance.player;
            if (player != null) {
                // 使用 opCode 3 表示模型变更
                SkinLayers3DNetworkPack.sendToServer(3, player.getUUID(), modelName.hashCode());
            }
        });
        
        // 主配置轮盘按键事件（按住打开，松开选择）
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (MCinstance.player == null || MCinstance.screen != null && !(MCinstance.screen instanceof ConfigWheelScreen)) {
                configWheelKeyWasDown = false;
                return;
            }
            
            boolean keyDown = keyConfigWheel.isDown();
            if (keyDown && !configWheelKeyWasDown) {
                // 按下时打开轮盘
                int keyCode = keyConfigWheel.getDefaultKey().getValue();
                MCinstance.setScreen(new ConfigWheelScreen(keyCode));
            }
            configWheelKeyWasDown = keyDown;
        });
        
        // 女仆配置轮盘按键事件（对着女仆按住打开）
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (MCinstance.player == null || MCinstance.screen != null && !(MCinstance.screen instanceof MaidConfigWheelScreen)) {
                maidConfigWheelKeyWasDown = false;
                return;
            }
            
            boolean keyDown = keyMaidConfigWheel.isDown();
            if (keyDown && !maidConfigWheelKeyWasDown) {
                // 检测是否对着女仆
                tryOpenMaidConfigWheel(MCinstance);
            }
            maidConfigWheelKeyWasDown = keyDown;
        });
        
        // 注册女仆模型选择网络发送器
        MaidModelNetworkHandler.setNetworkSender((entityId, modelName) -> {
            LocalPlayer player = MCinstance.player;
            if (player != null) {
                SkinLayers3DNetworkPack.sendToServer(4, player.getUUID(), entityId);
            }
        });
        
        // 注册女仆动作网络发送器
        MaidActionNetworkHandler.setNetworkSender((entityId, animId) -> {
            LocalPlayer player = MCinstance.player;
            if (player != null) {
                logger.info("发送女仆动作到服务器: 实体={}, 动画={}", entityId, animId);
                SkinLayers3DNetworkPack.sendToServer(5, player.getUUID(), entityId);
            }
        });

        // 注册实体渲染器
        File[] modelDirs = new File(MCinstance.gameDirectory, "3d-skin").listFiles();
        if (modelDirs != null) {
            for (File i : modelDirs) {
                if (!i.getName().startsWith("EntityPlayer") && !i.getName().equals("DefaultAnim") && !i.getName().equals("Shader")) {
                    String mcEntityName = i.getName().replace('.', ':');
                    if (EntityType.byString(mcEntityName).isPresent())
                        EntityRendererRegistry.register(EntityType.byString(mcEntityName).get(), new SkinLayersRenderFactory<>(mcEntityName));
                    else
                        logger.warn(mcEntityName + " 实体不存在，跳过渲染注册");
                }
            }
        }

        // 注册网络接收器
        ClientPlayNetworking.registerGlobalReceiver(SkinLayers3DRegisterCommon.SKIN_S2C, (client, handler, buf, responseSender) -> {
            int opCode = buf.readInt();
            UUID playerUUID = buf.readUUID();
            int arg0 = buf.readInt();
            client.execute(() -> {
                SkinLayers3DNetworkPack.DoInClient(opCode, playerUUID, arg0);
            });
        });
        
        logger.info("SkinLayers3D 客户端注册完成");
    }

    /**
     * 尝试打开女仆配置轮盘
     */
    private static void tryOpenMaidConfigWheel(Minecraft mc) {
        HitResult hitResult = mc.hitResult;
        if (hitResult == null || hitResult.getType() != HitResult.Type.ENTITY) {
            return;
        }
        
        EntityHitResult entityHit = (EntityHitResult) hitResult;
        Entity target = entityHit.getEntity();
        
        String className = target.getClass().getName();
        if (className.contains("EntityMaid") || className.contains("touhoulittlemaid")) {
            String maidName = target.getName().getString();
            int keyCode = keyMaidConfigWheel.getDefaultKey().getValue();
            mc.setScreen(new MaidConfigWheelScreen(target.getUUID(), target.getId(), maidName, keyCode));
            logger.info("打开女仆配置轮盘: {} (ID: {})", maidName, target.getId());
        }
    }
}

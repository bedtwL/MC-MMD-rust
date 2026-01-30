package com.shiroha.skinlayers3d.forge.register;

import com.shiroha.skinlayers3d.forge.config.ModConfigScreen;
import com.shiroha.skinlayers3d.forge.network.SkinLayers3DNetworkPack;
import com.shiroha.skinlayers3d.maid.MaidActionNetworkHandler;
import com.shiroha.skinlayers3d.maid.MaidModelNetworkHandler;
import com.shiroha.skinlayers3d.renderer.render.SkinLayersRenderFactory;
import com.shiroha.skinlayers3d.ui.ActionWheelNetworkHandler;
import com.shiroha.skinlayers3d.ui.ConfigWheelScreen;
import com.shiroha.skinlayers3d.ui.MaidConfigWheelScreen;
import com.mojang.blaze3d.platform.InputConstants;
import java.io.File;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.KeyMapping;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.client.settings.KeyConflictContext;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.EntityRenderersEvent.RegisterRenderers;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.fml.common.Mod;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.glfw.GLFW;

/**
 * Forge 客户端注册
 * 负责按键绑定、网络通信和实体渲染器注册
 */
@Mod.EventBusSubscriber(value = Dist.CLIENT)
public class SkinLayers3DRegisterClient {
    static final Logger logger = LogManager.getLogger();
    
    // 主配置轮盘按键 (Alt，可自定义)
    static KeyMapping keyConfigWheel = new KeyMapping("key.skinlayers3d.config_wheel", 
        KeyConflictContext.IN_GAME, InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_LEFT_ALT, "key.categories.skinlayers3d");
    
    // 女仆配置轮盘按键 (B，对着女仆时生效)
    static KeyMapping keyMaidConfigWheel = new KeyMapping("key.skinlayers3d.maid_config_wheel", 
        KeyConflictContext.IN_GAME, InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_B, "key.categories.skinlayers3d");
    
    // 追踪按键状态
    private static boolean configWheelKeyWasDown = false;
    private static boolean maidConfigWheelKeyWasDown = false;

    public static void Register() {
        Minecraft MCinstance = Minecraft.getInstance();
        RegisterRenderers RR = new RegisterRenderers();
        RegisterKeyMappingsEvent RKE = new RegisterKeyMappingsEvent(MCinstance.options);
        
        // 注册按键（仅两个）
        RKE.register(keyConfigWheel);
        RKE.register(keyMaidConfigWheel);
        
        // 设置模组设置界面工厂
        ConfigWheelScreen.setModSettingsScreenFactory(() -> ModConfigScreen.create(null));

        // 注册动作轮盘网络发送器
        ActionWheelNetworkHandler.setNetworkSender(animId -> {
            LocalPlayer player = MCinstance.player;
            if (player != null) {
                // 使用字符串传输动画ID，支持中文文件名
                logger.info("发送动作到服务器: " + animId);
                SkinLayers3DRegisterCommon.channel.sendToServer(new SkinLayers3DNetworkPack(1, player.getUUID(), animId));
            }
        });
        
        // 注册模型选择网络发送器
        com.shiroha.skinlayers3d.ui.ModelSelectorNetworkHandler.setNetworkSender(modelName -> {
            LocalPlayer player = MCinstance.player;
            if (player != null) {
                // 使用 opCode 3 表示模型变更
                SkinLayers3DRegisterCommon.channel.sendToServer(new SkinLayers3DNetworkPack(3, player.getUUID(), modelName.hashCode()));
            }
        });
        
        // 注册女仆模型选择网络发送器
        MaidModelNetworkHandler.setNetworkSender((entityId, modelName) -> {
            LocalPlayer player = MCinstance.player;
            if (player != null) {
                // 使用 opCode 4 表示女仆模型变更，data 字段存储 entityId
                SkinLayers3DRegisterCommon.channel.sendToServer(new SkinLayers3DNetworkPack(4, player.getUUID(), entityId, modelName));
            }
        });
        
        // 注册女仆动作网络发送器
        MaidActionNetworkHandler.setNetworkSender((entityId, animId) -> {
            LocalPlayer player = MCinstance.player;
            if (player != null) {
                logger.info("发送女仆动作到服务器: 实体={}, 动画={}", entityId, animId);
                SkinLayers3DRegisterCommon.channel.sendToServer(new SkinLayers3DNetworkPack(5, player.getUUID(), entityId, animId));
            }
        });

        // 注册实体渲染器
        File[] modelDirs = new File(MCinstance.gameDirectory, "3d-skin").listFiles();
        if (modelDirs != null) {
            for (File i : modelDirs) {
                if (!i.getName().startsWith("EntityPlayer") && !i.getName().equals("DefaultAnim") && !i.getName().equals("Shader")) {
                    String mcEntityName = i.getName().replace('.', ':');
                    if (EntityType.byString(mcEntityName).isPresent()){
                        RR.registerEntityRenderer(EntityType.byString(mcEntityName).get(), new SkinLayersRenderFactory<>(mcEntityName));
                        logger.info(mcEntityName + " 实体存在，注册渲染器");
                    }else{
                        logger.warn(mcEntityName + " 实体不存在，跳过渲染注册");
                    }
                }
            }
        }
        logger.info("SkinLayers3D 客户端注册完成");
    }
    
    /**
     * 按键事件处理 - 主配置轮盘和女仆配置轮盘
     */
    @OnlyIn(Dist.CLIENT)
    @SubscribeEvent
    public static void onKeyInput(InputEvent.Key event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        
        // 主配置轮盘
        if (mc.screen == null || mc.screen instanceof ConfigWheelScreen) {
            boolean keyDown = keyConfigWheel.isDown();
            if (keyDown && !configWheelKeyWasDown) {
                int keyCode = keyConfigWheel.getDefaultKey().getValue();
                mc.setScreen(new ConfigWheelScreen(keyCode));
            }
            configWheelKeyWasDown = keyDown;
        }
        
        // 女仆配置轮盘
        if (mc.screen == null || mc.screen instanceof MaidConfigWheelScreen) {
            boolean keyDown = keyMaidConfigWheel.isDown();
            if (keyDown && !maidConfigWheelKeyWasDown) {
                tryOpenMaidConfigWheel(mc);
            }
            maidConfigWheelKeyWasDown = keyDown;
        }
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

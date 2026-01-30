package com.shiroha.skinlayers3d.maid;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * 女仆动作网络通信处理器
 * 用于在客户端和服务器之间同步女仆的动作
 */
public class MaidActionNetworkHandler {
    private static final Logger logger = LogManager.getLogger();
    
    // 网络发送器：(实体ID, 动画ID) -> 发送到服务器
    private static MaidActionSender networkSender;

    @FunctionalInterface
    public interface MaidActionSender {
        void send(int entityId, String animId);
    }

    /**
     * 设置网络发送器（由平台特定代码调用）
     */
    public static void setNetworkSender(MaidActionSender sender) {
        networkSender = sender;
        logger.info("女仆动作网络发送器已设置");
    }

    /**
     * 发送女仆动作到服务器
     */
    public static void sendMaidAction(int entityId, String animId) {
        if (networkSender != null) {
            networkSender.send(entityId, animId);
            logger.info("发送女仆动作到服务器: 实体={}, 动画={}", entityId, animId);
        } else {
            logger.warn("网络发送器未设置，无法发送女仆动作");
        }
    }
}

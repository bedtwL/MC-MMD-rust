package com.shiroha.mmdskin.renderer.camera;

import com.shiroha.mmdskin.NativeFunc;
import com.shiroha.mmdskin.renderer.model.MMDModelManager;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joml.Vector3f;

/**
 * MMD 舞台模式相机控制器（单例）
 * 
 * 状态机：INACTIVE ──startStage()──> INTRO ──过渡完成──> PLAYING ──播放完/ESC──> INACTIVE
 * 
 * INTRO 阶段：从当前相机位置平滑过渡到模型正前方的展示位置
 * PLAYING 阶段：按 VMD 相机数据驱动
 * 
 * 通过 Mixin 在相机 setup 时覆盖位置/旋转/FOV。
 */
public class MMDCameraController {
    private static final Logger logger = LogManager.getLogger();
    
    private static final MMDCameraController INSTANCE = new MMDCameraController();
    
    // MMD 单位到 Minecraft 单位的缩放（1 MMD 单位 ≈ 0.08 MC 方块）
    private static final float MMD_TO_MC_SCALE = 0.08f;
    // VMD 30fps
    private static final float VMD_FPS = 30.0f;
    
    // 状态机
    private enum StageState { INACTIVE, INTRO, PLAYING }
    private StageState state = StageState.INACTIVE;
    
    // 视角保存/恢复
    private CameraType savedCameraType = null;
    
    // 模型名（用于停止时重载）
    private String modelName = null;
    
    private boolean cinematicMode = false;
    private boolean previousHideGui = false;
    
    // 帧控制
    private float currentFrame = 0.0f;
    private float maxFrame = 0.0f;
    private float playbackSpeed = 1.0f;
    
    // 相机 VMD 句柄（可独立于动作 VMD）
    private long cameraAnimHandle = 0;
    // 动作 VMD 句柄（用于同步帧）
    private long motionAnimHandle = 0;
    
    // 模型句柄（用于禁用/恢复自动行为）
    private long modelHandle = 0;
    
    // 相机数据
    private final MMDCameraData cameraData = new MMDCameraData();
    
    // 玩家位置偏移（相机基于玩家位置）
    private double anchorX, anchorY, anchorZ;
    
    // 计算后的相机世界坐标
    private double cameraX, cameraY, cameraZ;
    private float cameraPitch, cameraYaw, cameraRoll;
    private float cameraFov = 70.0f;
    
    // 时间追踪
    private long lastTickTimeNs = 0;
    
    // 开场过渡
    private static final float INTRO_DURATION = 1.0f;
    private float introElapsed = 0.0f;
    private double introStartX, introStartY, introStartZ;
    private float introStartPitch, introStartYaw, introStartFov;
    private double introEndX, introEndY, introEndZ;
    private float introEndPitch, introEndYaw, introEndFov;
    
    private MMDCameraController() {}
    
    public static MMDCameraController getInstance() {
        return INSTANCE;
    }
    
    /**
     * 启动舞台模式
     * @param motionAnim 动作 VMD 动画句柄（已设置到模型）
     * @param cameraAnim 相机 VMD 动画句柄（含相机数据），0 表示使用 motionAnim 的内嵌相机
     * @param cinematic 是否影院模式（隐藏 HUD）
     * @param modelHandle 模型句柄（用于禁用/恢复自动行为），0 表示无模型
     * @param modelName 模型名称（用于停止时重载），null 表示不重载
     */
    public void startStage(long motionAnim, long cameraAnim, boolean cinematic, 
                           long modelHandle, String modelName) {
        NativeFunc nf = NativeFunc.GetInst();
        
        this.motionAnimHandle = motionAnim;
        
        // 确定相机数据来源
        if (cameraAnim != 0 && nf.HasCameraData(cameraAnim)) {
            this.cameraAnimHandle = cameraAnim;
        } else if (motionAnim != 0 && nf.HasCameraData(motionAnim)) {
            this.cameraAnimHandle = motionAnim;
        } else {
            logger.warn("[舞台模式] 没有可用的相机数据");
            return;
        }
        
        this.maxFrame = nf.GetAnimMaxFrame(this.cameraAnimHandle);
        this.currentFrame = 0.0f;
        this.cinematicMode = cinematic;
        this.modelName = modelName;
        this.cameraData.setAnimHandle(this.cameraAnimHandle);
        
        Minecraft mc = Minecraft.getInstance();
        
        // 保存当前视角并强制第三人称（确保 MC 渲染玩家模型）
        this.savedCameraType = mc.options.getCameraType();
        mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
        
        // 记录玩家当前位置作为锚点
        if (mc.player != null) {
            this.anchorX = mc.player.getX();
            this.anchorY = mc.player.getY();
            this.anchorZ = mc.player.getZ();
        }
        
        // 影院模式：隐藏 HUD
        if (cinematic) {
            this.previousHideGui = mc.options.hideGui;
            mc.options.hideGui = true;
        }
        
        // 禁用自动眨眼和视线追踪（避免与表情VMD冲突）
        this.modelHandle = modelHandle;
        if (modelHandle != 0) {
            nf.SetAutoBlinkEnabled(modelHandle, false);
            nf.SetEyeTrackingEnabled(modelHandle, false);
        }
        
        // 计算开场过渡的起点和终点
        computeIntroTransition(mc);
        
        this.introElapsed = 0.0f;
        this.lastTickTimeNs = System.nanoTime();
        this.state = StageState.INTRO;
        
        // 立即设置相机到起点位置（避免第一帧跳动）
        this.cameraX = introStartX;
        this.cameraY = introStartY;
        this.cameraZ = introStartZ;
        this.cameraPitch = introStartPitch;
        this.cameraYaw = introStartYaw;
        this.cameraFov = introStartFov;
        
        logger.info("[舞台模式] 启动: 相机帧={}, 影院={}, 模型={}", maxFrame, cinematic, modelHandle);
    }
    
    /**
     * 计算开场过渡的起点（当前相机）和终点（模型正前方上方展示位置）
     */
    private void computeIntroTransition(Minecraft mc) {
        // 起点：当前相机位置/角度
        if (mc.gameRenderer != null && mc.gameRenderer.getMainCamera() != null) {
            var cam = mc.gameRenderer.getMainCamera();
            introStartX = cam.getPosition().x;
            introStartY = cam.getPosition().y;
            introStartZ = cam.getPosition().z;
            introStartPitch = cam.getXRot();
            introStartYaw = cam.getYRot();
        } else if (mc.player != null) {
            introStartX = mc.player.getX();
            introStartY = mc.player.getEyeY();
            introStartZ = mc.player.getZ();
            introStartPitch = mc.player.getXRot();
            introStartYaw = mc.player.getYRot();
        }
        introStartFov = (float) mc.options.fov().get();
        
        // 终点：玩家正前方 2 格 + 向上 1.5 格
        if (mc.player != null) {
            float yawRad = (float) Math.toRadians(mc.player.getYRot());
            introEndX = anchorX - Math.sin(yawRad) * 2.0;
            introEndY = anchorY + 1.5;
            introEndZ = anchorZ + Math.cos(yawRad) * 2.0;
            // 朝向看回玩家眼睛高度
            introEndYaw = mc.player.getYRot() + 180.0f;
            // 向下看约 17 度
            introEndPitch = -17.0f;
        } else {
            introEndX = introStartX;
            introEndY = introStartY;
            introEndZ = introStartZ;
            introEndYaw = introStartYaw;
            introEndPitch = introStartPitch;
        }
        introEndFov = 70.0f;
    }
    
    /**
     * 停止舞台模式
     */
    public void stopStage() {
        if (state == StageState.INACTIVE) return;
        
        Minecraft mc = Minecraft.getInstance();
        
        // 恢复视角
        if (savedCameraType != null) {
            mc.options.setCameraType(savedCameraType);
            savedCameraType = null;
        }
        
        // 恢复 HUD
        if (cinematicMode) {
            mc.options.hideGui = previousHideGui;
        }
        
        // 恢复自动眨眼和视线追踪
        if (this.modelHandle != 0) {
            NativeFunc nf = NativeFunc.GetInst();
            nf.SetAutoBlinkEnabled(this.modelHandle, true);
            nf.SetEyeTrackingEnabled(this.modelHandle, true);
        }
        
        // 强制重载模型（清除 VMD 残留姿势）
        if (this.modelName != null && !this.modelName.isEmpty()) {
            MMDModelManager.forceReloadModel(this.modelName);
            logger.info("[舞台模式] 模型已重载: {}", this.modelName);
        }
        
        // 清理动画句柄
        NativeFunc nf = NativeFunc.GetInst();
        if (this.motionAnimHandle != 0) {
            nf.DeleteAnimation(this.motionAnimHandle);
        }
        if (this.cameraAnimHandle != 0 && this.cameraAnimHandle != this.motionAnimHandle) {
            nf.DeleteAnimation(this.cameraAnimHandle);
        }
        
        this.state = StageState.INACTIVE;
        this.cameraAnimHandle = 0;
        this.motionAnimHandle = 0;
        this.modelHandle = 0;
        this.modelName = null;
        this.currentFrame = 0.0f;
        this.maxFrame = 0.0f;
        
        logger.info("[舞台模式] 已停止");
    }
    
    /**
     * 每帧更新（由 Mixin 在 Camera.setup 中调用）
     */
    public void updateCamera() {
        switch (state) {
            case INTRO:   updateIntro();   break;
            case PLAYING: updatePlaying(); break;
            default: break;
        }
    }
    
    /**
     * INTRO 阶段：从当前相机平滑过渡到展示位置
     */
    private void updateIntro() {
        long now = System.nanoTime();
        float deltaTime = (now - lastTickTimeNs) / 1_000_000_000.0f;
        lastTickTimeNs = now;
        deltaTime = Math.min(deltaTime, 0.1f);
        
        introElapsed += deltaTime;
        float t = smoothstep(introElapsed / INTRO_DURATION);
        
        // 插值位置
        cameraX = lerp(introStartX, introEndX, t);
        cameraY = lerp(introStartY, introEndY, t);
        cameraZ = lerp(introStartZ, introEndZ, t);
        
        // 插值角度
        cameraPitch = lerp(introStartPitch, introEndPitch, t);
        cameraYaw = lerpAngle(introStartYaw, introEndYaw, t);
        cameraFov = lerp(introStartFov, introEndFov, t);
        cameraRoll = 0.0f;
        
        // 过渡完成 → 切换到 PLAYING
        if (introElapsed >= INTRO_DURATION) {
            state = StageState.PLAYING;
            currentFrame = 0.0f;
            lastTickTimeNs = System.nanoTime();
            logger.info("[舞台模式] INTRO 完成, 进入 PLAYING");
        }
    }
    
    /**
     * PLAYING 阶段：VMD 帧推进 + 相机数据读取
     */
    private void updatePlaying() {
        // 计算真实 delta time
        long now = System.nanoTime();
        float deltaTime = (now - lastTickTimeNs) / 1_000_000_000.0f;
        lastTickTimeNs = now;
        
        // 限制最大 delta（防止暂停后跳帧）
        deltaTime = Math.min(deltaTime, 0.1f);
        
        // 推进帧
        currentFrame += deltaTime * VMD_FPS * playbackSpeed;
        
        // 播放完毕自动停止
        if (currentFrame >= maxFrame) {
            currentFrame = maxFrame;
            stopStage();
            return;
        }
        
        // 更新相机数据
        cameraData.update(currentFrame);
        
        // MMD 坐标 -> Minecraft 世界坐标
        Vector3f mmdPos = cameraData.getPosition();
        cameraX = anchorX + mmdPos.x * MMD_TO_MC_SCALE;
        cameraY = anchorY + mmdPos.y * MMD_TO_MC_SCALE;
        cameraZ = anchorZ + mmdPos.z * MMD_TO_MC_SCALE;
        
        // 欧拉角转换（弧度 -> 度）
        cameraPitch = (float) Math.toDegrees(cameraData.getPitch());
        cameraYaw = (float) Math.toDegrees(cameraData.getYaw());
        cameraRoll = (float) Math.toDegrees(cameraData.getRoll());
        
        // FOV
        cameraFov = cameraData.getFov();
    }
    
    /**
     * 检查是否按下 ESC 键退出舞台模式（由 Mixin 每帧调用）
     */
    public void checkEscapeKey() {
        if (state == StageState.INACTIVE) return;
        Minecraft mc = Minecraft.getInstance();
        long window = mc.getWindow().getWindow();
        if (org.lwjgl.glfw.GLFW.glfwGetKey(window, org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) == org.lwjgl.glfw.GLFW.GLFW_PRESS) {
            stopStage();
        }
    }
    
    // ==================== 缓动工具 ====================
    
    private static float smoothstep(float t) {
        t = Math.max(0, Math.min(1, t));
        return t * t * (3 - 2 * t);
    }
    
    private static double lerp(double a, double b, float t) {
        return a + (b - a) * t;
    }
    
    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }
    
    /**
     * 角度插值（处理 360° 环绕）
     */
    private static float lerpAngle(float a, float b, float t) {
        float diff = ((b - a) % 360 + 540) % 360 - 180;
        return a + diff * t;
    }
    
    // ==================== 状态查询 ====================
    
    public boolean isActive() {
        return state != StageState.INACTIVE;
    }
    
    public boolean isPlaying() {
        return state == StageState.PLAYING;
    }
    
    public boolean isCinematicMode() {
        return cinematicMode;
    }
    
    public float getCurrentFrame() {
        return currentFrame;
    }
    
    public float getMaxFrame() {
        return maxFrame;
    }
    
    public float getProgress() {
        return maxFrame > 0 ? currentFrame / maxFrame : 0.0f;
    }
    
    // ==================== 相机参数（供 Mixin 读取） ====================
    
    public double getCameraX() { return cameraX; }
    public double getCameraY() { return cameraY; }
    public double getCameraZ() { return cameraZ; }
    
    public float getCameraPitch() { return cameraPitch; }
    public float getCameraYaw() { return cameraYaw; }
    public float getCameraRoll() { return cameraRoll; }
    
    public float getCameraFov() { return cameraFov; }
    
    public void setPlaybackSpeed(float speed) {
        this.playbackSpeed = speed;
    }
    
    public float getPlaybackSpeed() {
        return playbackSpeed;
    }
}

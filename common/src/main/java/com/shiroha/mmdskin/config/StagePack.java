package com.shiroha.mmdskin.config;

import com.shiroha.mmdskin.NativeFunc;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 舞台包数据类
 * 每个子文件夹视为一个舞台包，包含若干 VMD 文件
 */
public class StagePack {
    private static final Logger logger = LogManager.getLogger();
    
    private final String name;
    private final String folderPath;
    private final List<VmdFileInfo> vmdFiles;
    
    public StagePack(String name, String folderPath, List<VmdFileInfo> vmdFiles) {
        this.name = name;
        this.folderPath = folderPath;
        this.vmdFiles = Collections.unmodifiableList(vmdFiles);
    }
    
    public String getName() { return name; }
    public String getFolderPath() { return folderPath; }
    public List<VmdFileInfo> getVmdFiles() { return vmdFiles; }
    
    /**
     * 是否有可用的动作 VMD（至少 1 个含骨骼或表情数据的 VMD）
     */
    public boolean hasMotionVmd() {
        for (VmdFileInfo info : vmdFiles) {
            if (info.hasBones || info.hasMorphs) return true;
        }
        return false;
    }
    
    /**
     * 是否有相机 VMD
     */
    public boolean hasCameraVmd() {
        for (VmdFileInfo info : vmdFiles) {
            if (info.hasCamera) return true;
        }
        return false;
    }
    
    /**
     * 扫描 StageAnim 目录下所有子文件夹，每个子文件夹生成一个 StagePack
     */
    public static List<StagePack> scan(File stageAnimDir) {
        List<StagePack> packs = new ArrayList<>();
        if (!stageAnimDir.exists() || !stageAnimDir.isDirectory()) return packs;
        
        File[] subDirs = stageAnimDir.listFiles(File::isDirectory);
        if (subDirs == null) return packs;
        
        NativeFunc nf = NativeFunc.GetInst();
        
        for (File dir : subDirs) {
            List<VmdFileInfo> files = scanVmdFiles(dir, nf);
            if (!files.isEmpty()) {
                packs.add(new StagePack(dir.getName(), dir.getAbsolutePath(), files));
            }
        }
        
        // 按名称排序
        packs.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
        
        logger.info("[StagePack] 扫描到 {} 个舞台包", packs.size());
        return packs;
    }
    
    /**
     * 扫描目录中的所有 VMD 文件，通过临时加载检测数据类型
     */
    private static List<VmdFileInfo> scanVmdFiles(File dir, NativeFunc nf) {
        List<VmdFileInfo> results = new ArrayList<>();
        
        File[] files = dir.listFiles((d, name) -> name.toLowerCase().endsWith(PathConstants.VMD_EXTENSION));
        if (files == null) return results;
        
        for (File file : files) {
            String path = file.getAbsolutePath();
            
            // 临时加载检测数据类型
            long tempAnim = nf.LoadAnimation(0, path);
            if (tempAnim == 0) continue;
            
            boolean hasCamera = nf.HasCameraData(tempAnim);
            boolean hasBones = nf.HasBoneData(tempAnim);
            boolean hasMorphs = nf.HasMorphData(tempAnim);
            nf.DeleteAnimation(tempAnim);
            
            results.add(new VmdFileInfo(file.getName(), path, hasCamera, hasBones, hasMorphs));
        }
        
        // 按文件名排序
        results.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
        return results;
    }
    
    /**
     * VMD 文件信息
     */
    public static class VmdFileInfo {
        public final String name;
        public final String path;
        public final boolean hasCamera;
        public final boolean hasBones;
        public final boolean hasMorphs;
        
        public VmdFileInfo(String name, String path, boolean hasCamera, boolean hasBones, boolean hasMorphs) {
            this.name = name;
            this.path = path;
            this.hasCamera = hasCamera;
            this.hasBones = hasBones;
            this.hasMorphs = hasMorphs;
        }
        
        /**
         * 获取类型标签（用于 UI 显示）
         */
        public String getTypeTag() {
            StringBuilder sb = new StringBuilder();
            if (hasCamera) sb.append("\uD83D\uDCF7"); // 📷
            if (hasBones) sb.append("\uD83E\uDDB4");  // 🦴
            if (hasMorphs) sb.append("\uD83D\uDE0A");  // 😊
            return sb.toString();
        }
    }
}

//! IK 求解器

use glam::{Vec3, Quat};

use super::bone::{Bone, IkConfig};

/// IK 求解器
#[derive(Clone, Debug)]
pub struct IkSolver {
    pub bone_index: usize,
    pub config: IkConfig,
    pub enabled: bool,
}

impl IkSolver {
    pub fn new(bone_index: usize, config: IkConfig) -> Self {
        Self {
            bone_index,
            config,
            enabled: true,
        }
    }
    
    /// 求解 IK（对应 C++ MMDIkSolver::Solve）
    pub fn solve(&self, bones: &mut [Bone]) {
        if !self.enabled {
            return;
        }
        
        let target_idx = self.config.target_bone as usize;
        if target_idx >= bones.len() {
            return;
        }
        
        // 初始化 IK 链（对应 C++ 的初始化循环）
        for link in &self.config.links {
            let link_idx = link.bone_index as usize;
            if link_idx < bones.len() {
                bones[link_idx].ik_rotate = Quat::IDENTITY;
                bones[link_idx].enable_ik = true;
                bones[link_idx].update_local_transform();
                self.update_single_bone_global(bones, link_idx);
            }
        }
        
        // 获取目标位置（IK 骨骼的全局位置）
        let target_pos = bones[self.bone_index].global_transform.col(3).truncate();
        
        let mut max_dist = f32::MAX;
        let mut saved_ik_rotates: Vec<Quat> = self.config.links.iter()
            .map(|_| Quat::IDENTITY)
            .collect();
        
        for iteration in 0..self.config.iterations {
            self.solve_core(bones, target_idx, iteration);
            
            // 检查是否改善
            let effector_pos = bones[target_idx].global_transform.col(3).truncate();
            let dist = (effector_pos - target_pos).length();
            
            if dist < max_dist {
                max_dist = dist;
                // 保存最佳结果
                for (i, link) in self.config.links.iter().enumerate() {
                    let link_idx = link.bone_index as usize;
                    if link_idx < bones.len() {
                        saved_ik_rotates[i] = bones[link_idx].ik_rotate;
                    }
                }
            } else {
                // 恢复最佳结果
                for (i, link) in self.config.links.iter().enumerate() {
                    let link_idx = link.bone_index as usize;
                    if link_idx < bones.len() {
                        bones[link_idx].ik_rotate = saved_ik_rotates[i];
                        bones[link_idx].update_local_transform();
                        self.update_single_bone_global(bones, link_idx);
                    }
                }
                break;
            }
        }
    }
    
    /// IK 求解核心（对应 C++ MMDIkSolver::SolveCore）
    fn solve_core(&self, bones: &mut [Bone], target_idx: usize, _iteration: u32) {
        let ik_pos = bones[self.bone_index].global_transform.col(3).truncate();
        
        for link in &self.config.links {
            let link_idx = link.bone_index as usize;
            if link_idx >= bones.len() {
                continue;
            }
            
            // 跳过目标骨骼本身
            if link_idx == target_idx {
                continue;
            }
            
            // 获取效果器位置（目标骨骼的全局位置）
            let effector_pos = bones[target_idx].global_transform.col(3).truncate();
            
            // 获取链接骨骼的全局变换
            let link_global = bones[link_idx].global_transform;
            let inv_chain = link_global.inverse();
            
            // 在链接骨骼的本地空间中计算（与 C++ saba 一致）
            let chain_ik_pos = inv_chain.transform_point3(ik_pos);
            let chain_target_pos = inv_chain.transform_point3(effector_pos);
            
            let chain_ik_vec = chain_ik_pos.normalize_or_zero();
            let chain_target_vec = chain_target_pos.normalize_or_zero();
            
            if chain_ik_vec.length_squared() < 1e-6 || chain_target_vec.length_squared() < 1e-6 {
                continue;
            }
            
            // 计算旋转角度
            let dot = chain_target_vec.dot(chain_ik_vec).clamp(-1.0, 1.0);
            let angle = dot.acos();
            
            if angle < 1e-3_f32.to_radians() {
                continue;
            }
            
            // 限制角度
            let limited_angle = angle.clamp(-self.config.limit_angle, self.config.limit_angle);
            
            // 计算旋转轴
            let cross = chain_target_vec.cross(chain_ik_vec).normalize_or_zero();
            if cross.length_squared() < 1e-6 {
                continue;
            }
            
            // 计算旋转
            let rot = Quat::from_axis_angle(cross, limited_angle);
            
            // 与 C++ saba 一致：chainRot = IKRotate * AnimateRotate * rot
            let chain_rot = bones[link_idx].ik_rotate * bones[link_idx].animation_rotate * rot;
            
            // 应用角度限制
            let chain_rot = if link.has_limits {
                self.apply_chain_angle_limits(chain_rot, &link.limit_min, &link.limit_max)
            } else {
                chain_rot
            };
            
            // 提取 IK 旋转：ikRot = chainRot * inverse(AnimateRotate)
            let ik_rot = chain_rot * bones[link_idx].animation_rotate.inverse();
            bones[link_idx].ik_rotate = ik_rot;
            
            // 更新变换
            bones[link_idx].update_local_transform();
            self.update_single_bone_global(bones, link_idx);
        }
    }
    
    /// 应用链旋转的角度限制（对应 C++ 的角度限制逻辑）
    fn apply_chain_angle_limits(&self, chain_rot: Quat, min: &Vec3, max: &Vec3) -> Quat {
        let (mut euler_x, mut euler_y, mut euler_z) = chain_rot.to_euler(glam::EulerRot::XYZ);
        
        euler_x = euler_x.clamp(min.x, max.x);
        euler_y = euler_y.clamp(min.y, max.y);
        euler_z = euler_z.clamp(min.z, max.z);
        
        Quat::from_euler(glam::EulerRot::XYZ, euler_x, euler_y, euler_z)
    }
    
    /// 应用 IK 角度限制（已弃用，保留兼容）
    #[allow(dead_code)]
    fn apply_ik_angle_limits(&self, bone: &mut Bone, min: &Vec3, max: &Vec3) {
        let (mut euler_x, mut euler_y, mut euler_z) = bone.ik_rotate.to_euler(glam::EulerRot::XYZ);
        
        euler_x = euler_x.clamp(min.x, max.x);
        euler_y = euler_y.clamp(min.y, max.y);
        euler_z = euler_z.clamp(min.z, max.z);
        
        bone.ik_rotate = Quat::from_euler(glam::EulerRot::XYZ, euler_x, euler_y, euler_z);
    }
    
    /// 更新单个骨骼的全局变换（不递归，避免与 BoneManager 步骤4冲突）
    fn update_single_bone_global(&self, bones: &mut [Bone], idx: usize) {
        if idx >= bones.len() {
            return;
        }
        
        // 只更新当前骨骼，不递归更新子骨骼
        // 子骨骼由 BoneManager::update_transforms 步骤4统一处理
        let parent_idx = bones[idx].parent_index;
        if parent_idx >= 0 && (parent_idx as usize) < bones.len() {
            let parent_global = bones[parent_idx as usize].global_transform;
            bones[idx].global_transform = parent_global * bones[idx].local_transform;
        } else {
            bones[idx].global_transform = bones[idx].local_transform;
        }
    }
    
    /// 更新变换链
    #[allow(dead_code)]
    fn update_chain_transforms(&self, bones: &mut [Bone], start_idx: usize) {
        // 更新从 start_idx 开始的变换链
        let mut indices_to_update = vec![start_idx];
        
        // 收集需要更新的骨骼
        for link in &self.config.links {
            let idx = link.bone_index as usize;
            if idx < bones.len() && !indices_to_update.contains(&idx) {
                indices_to_update.push(idx);
            }
        }
        
        indices_to_update.push(self.config.target_bone as usize);
        
        // 更新全局变换
        for &idx in &indices_to_update {
            if idx >= bones.len() {
                continue;
            }
            
            let parent_idx = bones[idx].parent_index;
            if parent_idx >= 0 && (parent_idx as usize) < bones.len() {
                let parent_global = bones[parent_idx as usize].global_transform;
                bones[idx].global_transform = parent_global * bones[idx].local_transform;
            } else {
                bones[idx].global_transform = bones[idx].local_transform;
            }
        }
    }
}

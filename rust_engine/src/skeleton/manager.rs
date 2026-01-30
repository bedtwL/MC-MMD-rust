//! 骨骼管理器

use glam::{Vec3, Quat, Mat4};
use std::collections::{HashMap, HashSet};

use super::{Bone, IkSolver};

/// 骨骼管理器
pub struct BoneManager {
    bones: Vec<Bone>,
    name_to_index: HashMap<String, usize>,
    sorted_indices: Vec<usize>,
    ik_solvers: Vec<IkSolver>,
    skinning_matrices: Vec<Mat4>,
    /// 物理驱动的骨骼索引集合（这些骨骼的变换由物理系统控制，不应被普通骨骼更新覆盖）
    physics_bone_indices: HashSet<usize>,
}

impl BoneManager {
    pub fn new() -> Self {
        Self {
            bones: Vec::new(),
            name_to_index: HashMap::new(),
            sorted_indices: Vec::new(),
            ik_solvers: Vec::new(),
            skinning_matrices: Vec::new(),
            physics_bone_indices: HashSet::new(),
        }
    }
    
    /// 设置物理骨骼索引集合
    pub fn set_physics_bone_indices(&mut self, indices: HashSet<usize>) {
        self.physics_bone_indices = indices;
    }
    
    /// 清除物理骨骼索引集合
    pub fn clear_physics_bone_indices(&mut self) {
        self.physics_bone_indices.clear();
    }
    
    /// 添加骨骼
    pub fn add_bone(&mut self, bone: Bone) {
        let index = self.bones.len();
        self.name_to_index.insert(bone.name.clone(), index);
        self.bones.push(bone);
    }
    
    /// 构建骨骼层级并计算逆绑定矩阵
    /// 与C++版本(PMXModel.cpp)完全一致的实现
    pub fn build_hierarchy(&mut self) {
        let bone_count = self.bones.len();
        if bone_count == 0 {
            return;
        }
        
        // 按变换层级排序（与C++ m_sortedNodes 一致）
        self.sorted_indices = (0..bone_count).collect();
        self.sorted_indices.sort_by(|&a, &b| {
            self.bones[a].transform_level.cmp(&self.bones[b].transform_level)
        });
        
        // 创建 IK 求解器
        for (i, bone) in self.bones.iter().enumerate() {
            if let Some(ref ik_config) = bone.ik_config {
                self.ik_solvers.push(IkSolver::new(i, ik_config.clone()));
            }
        }
        
        // 与C++完全一致的初始化流程：
        // C++: glm::mat4 init = glm::translate(glm::mat4(1), bone.m_position * glm::vec3(1, 1, -1));
        //      node->SetGlobalTransform(init);
        //      node->CalculateInverseInitTransform();  // m_inverseInit = inverse(m_global)
        
        for i in 0..bone_count {
            let pos = self.bones[i].initial_position;
            let parent_idx = self.bones[i].parent_index;
            
            // 1. 计算相对于父骨骼的偏移（用于本地变换）
            let offset = if parent_idx >= 0 && (parent_idx as usize) < bone_count {
                let parent_pos = self.bones[parent_idx as usize].initial_position;
                pos - parent_pos
            } else {
                pos
            };
            self.bones[i].bone_offset = offset;
            
            // 2. 与C++一致：初始全局变换直接从世界坐标创建
            // C++: glm::mat4 init = glm::translate(glm::mat4(1), bone.m_position * glm::vec3(1, 1, -1));
            let init_global = Mat4::from_translation(pos);
            self.bones[i].global_transform = init_global;
            
            // 3. 与C++一致：逆绑定矩阵 = inverse(初始全局变换)
            // C++: m_inverseInit = glm::inverse(m_global);
            self.bones[i].inverse_bind_matrix = init_global.inverse();
            
            // 4. 本地变换使用偏移
            self.bones[i].local_transform = Mat4::from_translation(offset);
        }
        
        // 初始化蒙皮矩阵缓冲区
        // 初始状态下：skinning_matrix = global * inverse_bind = I
        self.skinning_matrices = vec![Mat4::IDENTITY; bone_count];
        
        // 验证：初始状态下蒙皮矩阵应该是单位矩阵
        for i in 0..bone_count {
            self.skinning_matrices[i] = self.bones[i].get_skinning_matrix();
        }
    }
    
    /// 通过名称查找骨骼
    pub fn find_bone_by_name(&self, name: &str) -> Option<usize> {
        self.name_to_index.get(name).copied()
    }
    
    /// 获取骨骼数量
    pub fn bone_count(&self) -> usize {
        self.bones.len()
    }
    
    /// 获取骨骼
    pub fn get_bone(&self, index: usize) -> Option<&Bone> {
        self.bones.get(index)
    }

    /// 获取可变骨骼引用
    pub fn get_bone_mut(&mut self, index: usize) -> Option<&mut Bone> {
        self.bones.get_mut(index)
    }
    
    /// 重置所有骨骼变换
    pub fn reset_all_transforms(&mut self) {
        for bone in &mut self.bones {
            bone.reset_animation();
        }
    }
    
    /// 开始更新（对应 C++ MMDNode::BeginUpdateTransform）
    pub fn begin_update(&mut self) {
        for bone in &mut self.bones {
            bone.reset_animation();
        }
    }
    
    /// 结束更新
    pub fn end_update(&mut self) {
        // 计算蒙皮矩阵
        for i in 0..self.bones.len() {
            self.skinning_matrices[i] = self.bones[i].get_skinning_matrix();
        }
    }
    
    /// 更新骨骼变换（与C++版本一致）
    pub fn update_transforms(&mut self, after_physics: bool) {
        let sorted_indices: Vec<usize> = self.sorted_indices.clone();
        
        // 1. 更新本地变换（跳过物理驱动的骨骼）
        for &idx in &sorted_indices {
            if self.bones[idx].deform_after_physics != after_physics {
                continue;
            }
            // 跳过物理骨骼，它们的变换由物理系统控制
            if self.physics_bone_indices.contains(&idx) {
                continue;
            }
            self.bones[idx].update_local_transform();
        }
        
        // 2. 从根骨骼递归更新全局变换（与C++版本一致）
        for &idx in &sorted_indices {
            if self.bones[idx].deform_after_physics != after_physics {
                continue;
            }
            // 只对根骨骼调用，会递归更新子骨骼
            if self.bones[idx].parent_index < 0 {
                self.update_global_transform_recursive(idx);
            }
        }
        
        // 3. 处理附加变换和 IK
        for &idx in &sorted_indices {
            if self.bones[idx].deform_after_physics != after_physics {
                continue;
            }
            
            let needs_append = self.bones[idx].is_append_rotate || self.bones[idx].is_append_translate;
            let is_ik = self.bones[idx].is_ik;
            
            if needs_append {
                self.apply_append_transform(idx);
                self.update_global_transform_recursive(idx);
            }
            
            if is_ik {
                self.solve_ik(idx);
            }
        }
        
        // 4. 再次从根骨骼更新全局变换（与C++版本一致）
        for &idx in &sorted_indices {
            if self.bones[idx].deform_after_physics != after_physics {
                continue;
            }
            if self.bones[idx].parent_index < 0 {
                self.update_global_transform_recursive(idx);
            }
        }
    }
    
    /// 递归更新骨骼全局变换（与C++版本UpdateGlobalTransform一致）
    /// 
    /// 注意：跳过物理驱动的骨骼，它们的变换由物理系统控制
    fn update_global_transform_recursive(&mut self, index: usize) {
        // 跳过物理驱动的骨骼（它们的变换已由物理系统设置）
        if self.physics_bone_indices.contains(&index) {
            // 但仍需递归更新子骨骼，因为子骨骼可能不是物理骨骼
            let children: Vec<usize> = (0..self.bones.len())
                .filter(|&i| self.bones[i].parent_index == index as i32)
                .collect();
            for child_idx in children {
                self.update_global_transform_recursive(child_idx);
            }
            return;
        }
        
        // 计算当前骨骼的全局变换
        let parent_idx = self.bones[index].parent_index;
        if parent_idx >= 0 && (parent_idx as usize) < self.bones.len() {
            let parent_global = self.bones[parent_idx as usize].global_transform;
            self.bones[index].global_transform = parent_global * self.bones[index].local_transform;
        } else {
            self.bones[index].global_transform = self.bones[index].local_transform;
        }
        
        // 递归更新所有子骨骼
        let children: Vec<usize> = (0..self.bones.len())
            .filter(|&i| self.bones[i].parent_index == index as i32)
            .collect();
        
        for child_idx in children {
            self.update_global_transform_recursive(child_idx);
        }
    }
    
    /// 应用附加变换（对应 C++ PMXNode::UpdateAppendTransform）
    fn apply_append_transform(&mut self, index: usize) {
        let append_parent = self.bones[index].append_parent;
        if append_parent < 0 || (append_parent as usize) >= self.bones.len() {
            return;
        }
        
        let rate = self.bones[index].append_rate;
        let is_append_local = self.bones[index].is_append_local;
        let parent_idx = append_parent as usize;
        
        // 处理附加旋转
        if self.bones[index].is_append_rotate {
            let append_rotate = if is_append_local {
                self.bones[parent_idx].animation_rotate
            } else {
                if self.bones[parent_idx].append_parent >= 0 {
                    self.bones[parent_idx].append_rotate
                } else {
                    self.bones[parent_idx].animation_rotate
                }
            };
            
            let append_rotate = if self.bones[parent_idx].enable_ik {
                self.bones[parent_idx].ik_rotate * append_rotate
            } else {
                append_rotate
            };
            
            let weighted_rotate = Quat::IDENTITY.slerp(append_rotate, rate);
            self.bones[index].append_rotate = weighted_rotate;
        }
        
        // 处理附加平移
        if self.bones[index].is_append_translate {
            let append_translate = if is_append_local {
                self.bones[parent_idx].animation_translate
            } else {
                if self.bones[parent_idx].append_parent >= 0 {
                    self.bones[parent_idx].append_translate
                } else {
                    self.bones[parent_idx].animation_translate
                }
            };
            
            self.bones[index].append_translate = append_translate * rate;
        }
        
        self.bones[index].update_local_transform();
    }
    
    /// IK 求解（对应 C++ 的 IKSolver::Solve + UpdateGlobalTransform）
    fn solve_ik(&mut self, bone_index: usize) {
        // 查找对应的 IK 求解器
        let solver_idx = self.ik_solvers.iter().position(|s| s.bone_index == bone_index);
        if let Some(idx) = solver_idx {
            let solver = self.ik_solvers[idx].clone();
            solver.solve(&mut self.bones);
            
            // 与 C++ 一致：IK 求解后更新 IK 骨骼本身的全局变换
            self.update_global_transform_recursive(bone_index);
        }
    }
    
    /// 设置骨骼动画平移
    pub fn set_bone_translation(&mut self, index: usize, translation: Vec3) {
        if let Some(bone) = self.bones.get_mut(index) {
            bone.animation_translate = translation;
        }
    }
    
    /// 设置骨骼动画旋转
    pub fn set_bone_rotation(&mut self, index: usize, rotation: Quat) {
        if let Some(bone) = self.bones.get_mut(index) {
            bone.animation_rotate = rotation;
        }
    }
    
    /// 添加骨骼旋转
    pub fn add_bone_rotation(&mut self, index: usize, rotation: Quat) {
        if let Some(bone) = self.bones.get_mut(index) {
            bone.animation_rotate = bone.animation_rotate * rotation;
        }
    }
    
    /// 获取全局变换
    pub fn get_global_transform(&self, index: usize) -> Mat4 {
        self.bones.get(index).map(|b| b.global_transform).unwrap_or(Mat4::IDENTITY)
    }
    
    /// 设置全局变换（用于物理系统）
    /// 与saba一致：设置后需要反推局部变换并更新子骨骼
    /// 
    /// **关键修复**：同时更新 animation_rotate 和 animation_translate，
    /// 否则 update_local_transform() 会用动画数据覆盖物理设置的值！
    pub fn set_global_transform(&mut self, index: usize, transform: Mat4) {
        if index >= self.bones.len() {
            return;
        }
        
        // 1. 设置全局变换
        self.bones[index].global_transform = transform;
        
        // 2. 反推局部变换（与saba CalcLocalTransform一致）
        // local = inverse(parent_global) * global
        let parent_idx = self.bones[index].parent_index;
        let local_transform = if parent_idx >= 0 && (parent_idx as usize) < self.bones.len() {
            let parent_global = self.bones[parent_idx as usize].global_transform;
            parent_global.inverse() * transform
        } else {
            transform
        };
        self.bones[index].local_transform = local_transform;
        
        // 3. **关键修复**：从local_transform提取旋转和平移，更新animation_rotate和animation_translate
        // 这样 update_local_transform() 不会覆盖物理设置的值
        let (_, rotation, translation) = local_transform.to_scale_rotation_translation();
        self.bones[index].animation_rotate = rotation;
        self.bones[index].animation_translate = translation - self.bones[index].bone_offset;
        
        // 4. 递归更新子骨骼的全局变换
        self.update_children_global_transform(index);
    }
    
    /// 递归更新子骨骼的全局变换（不改变局部变换）
    fn update_children_global_transform(&mut self, parent_index: usize) {
        let parent_global = self.bones[parent_index].global_transform;
        
        // 找出所有直接子骨骼
        let children: Vec<usize> = (0..self.bones.len())
            .filter(|&i| self.bones[i].parent_index == parent_index as i32)
            .collect();
        
        for child_idx in children {
            // 子骨骼全局变换 = 父全局变换 * 子局部变换
            self.bones[child_idx].global_transform = parent_global * self.bones[child_idx].local_transform;
            // 递归更新孙骨骼
            self.update_children_global_transform(child_idx);
        }
    }
    
    /// 获取蒙皮矩阵数组
    pub fn get_skinning_matrices(&self) -> &[Mat4] {
        &self.skinning_matrices
    }

    /// 设置骨骼的物理变换（用于物理系统更新）
    pub fn set_bone_physics_transform(&mut self, index: usize, position: Vec3, rotation: Quat) {
        if index >= self.bones.len() {
            return;
        }
        let transform = Mat4::from_rotation_translation(rotation, position);
        self.set_global_transform(index, transform);
    }
    
    /// 设置全局变换（物理专用，不递归更新子骨骼）
    /// 
    /// 用于物理系统更新骨骼变换，避免父骨骼覆盖子骨骼的物理变换
    pub fn set_global_transform_physics(&mut self, index: usize, transform: Mat4) {
        if index >= self.bones.len() {
            return;
        }
        
        // 1. 设置全局变换
        self.bones[index].global_transform = transform;
        
        // 2. 反推局部变换
        let parent_idx = self.bones[index].parent_index;
        let local_transform = if parent_idx >= 0 && (parent_idx as usize) < self.bones.len() {
            let parent_global = self.bones[parent_idx as usize].global_transform;
            parent_global.inverse() * transform
        } else {
            transform
        };
        self.bones[index].local_transform = local_transform;
        
        // 3. 从 local_transform 提取旋转和平移，更新 animation_rotate 和 animation_translate
        let (_, rotation, translation) = local_transform.to_scale_rotation_translation();
        self.bones[index].animation_rotate = rotation;
        self.bones[index].animation_translate = translation - self.bones[index].bone_offset;
        
        // 注意：不调用 update_children_global_transform，因为子骨骼会由物理系统单独更新
    }
    
    /// 批量更新物理骨骼后，更新非物理骨骼的全局变换
    /// 
    /// 对于不在 physics_bone_indices 集合中的骨骼，如果其父骨骼已更新，则需要更新其全局变换
    pub fn update_non_physics_children(&mut self, physics_bone_indices: &std::collections::HashSet<usize>) {
        // 按排序顺序遍历，确保父骨骼先处理
        let sorted_indices: Vec<usize> = self.sorted_indices.clone();
        
        for &idx in &sorted_indices {
            // 跳过物理骨骼
            if physics_bone_indices.contains(&idx) {
                continue;
            }
            
            let parent_idx = self.bones[idx].parent_index;
            if parent_idx >= 0 {
                // 子骨骼全局变换 = 父全局变换 * 子局部变换
                let parent_global = self.bones[parent_idx as usize].global_transform;
                self.bones[idx].global_transform = parent_global * self.bones[idx].local_transform;
            }
        }
    }
}

impl Default for BoneManager {
    fn default() -> Self {
        Self::new()
    }
}

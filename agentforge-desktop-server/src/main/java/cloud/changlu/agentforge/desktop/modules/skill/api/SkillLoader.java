package cloud.changlu.agentforge.desktop.modules.skill.api;

import java.nio.file.Path;
import java.util.List;

/**
 * Skill 加载与管理的通用能力边界。
 *
 * <p>调用方只依赖本接口，不感知 Skill 来自本地文件系统、内置资源或其他存储实现。
 * 实现类负责 Skill 发现、优先级合并、Prompt 索引生成、详情读取与导入安全校验。
 */
public interface SkillLoader {

    /**
     * 返回全局 Skill 根路径。
     *
     * <p>该方法只解析路径，不要求目录已存在，也不应产生文件系统写入。
     */
    Path globalRoot();

    /**
     * 确保全局 Skill 根目录已创建并返回其路径。
     *
     * @return 可用的全局 Skill 根路径
     * @throws IllegalStateException 目录无法创建时抛出
     */
    Path ensureGlobalRoot();

    /**
     * 列出全局作用域中可用的 Skill。
     *
     * <p>仅返回全局 Skill，不合并任何工作空间 Skill，主要用于技能管理页面。
     */
    List<SkillMetadata> listGlobalSkills();

    /**
     * 列出指定工作空间中最终生效的 Skill。
     *
     * <p>实现应合并全局与工作空间 Skill；出现同名 Skill 时，工作空间版本优先。
     *
     * @param workspaceRoot 当前工作空间根路径；为 {@code null} 时只返回全局 Skill
     * @return 去重并按稳定顺序排列的有效 Skill 元数据
     */
    List<SkillMetadata> listEffectiveSkills(Path workspaceRoot);

    /**
     * 为 Agent 系统提示词生成当前可用 Skill 的精简索引。
     *
     * <p>索引只包含 Skill 名称、描述、作用域和可传给详情工具的路径，不应将完整
     * {@code SKILL.md} 正文一次性注入 Prompt。
     *
     * @param workspaceRoot 当前工作空间根路径
     * @return 可直接追加到系统提示词的索引；无 Skill 时返回空字符串
     */
    String loadSkillIndex(Path workspaceRoot);

    /**
     * 按需读取单个 Skill 的完整说明。
     *
     * <p>实现必须校验请求路径的作用域，禁止通过父目录或符号链接读取全局 Skill
     * 根目录和当前工作空间 Skill 根目录之外的文件。
     *
     * @param workspaceRoot 当前工作空间根路径，用于限定允许读取的工作空间 Skill
     * @param requestedPath Skill 目录或 {@code SKILL.md} 文件路径
     * @return 完整的 {@code SKILL.md} 文本
     * @throws IllegalArgumentException 路径为空、格式错误或文件超限时抛出
     * @throws SecurityException 请求路径超出允许的 Skill 根目录时抛出
     */
    String skill(Path workspaceRoot, String requestedPath);

    /**
     * 将外部 Skill 导入全局作用域。
     *
     * <p>输入可以是包含 {@code SKILL.md} 的目录，也可以是符合实现规范的压缩包。
     * 实现应校验路径穿越、符号链接、文件数量、总大小和同名冲突，且不得修改源内容。
     *
     * @param sourcePath Skill 目录或压缩包路径
     * @return 导入后的全局 Skill 元数据
     * @throws IllegalArgumentException 输入不是有效 Skill 或存在同名冲突时抛出
     * @throws IllegalStateException 创建目录、解压或复制失败时抛出
     */
    SkillMetadata importGlobalSkill(Path sourcePath);
}

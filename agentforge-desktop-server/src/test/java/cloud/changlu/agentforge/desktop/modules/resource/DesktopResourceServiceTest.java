package cloud.changlu.agentforge.desktop.modules.resource;

import cloud.changlu.agentforge.desktop.modules.resource.domain.ResolvedResource;
import cloud.changlu.agentforge.desktop.modules.resource.domain.ResourceRef;
import cloud.changlu.agentforge.desktop.modules.resource.service.DesktopResourceService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DesktopResourceServiceTest {

    private final DesktopResourceService service = new DesktopResourceService();

    @Test
    void resolvesImageAndDirectoryAndKeepsMarkerOrder(@TempDir Path dir) throws Exception {
        Path image = Files.write(dir.resolve("shot.png"), new byte[] {1, 2, 3});
        Path folder = Files.createDirectories(dir.resolve("docs"));
        String text = "先看 "
                + marker("image", image)
                + " 再处理 "
                + marker("folder", folder)
                + " 完成";

        List<ResolvedResource> resources = service.resolve(Arrays.asList(
                ref(image, "image"), ref(folder, "folder")));

        assertEquals(2, resources.size());
        assertEquals("image", resources.get(0).getType());
        assertEquals(3L, resources.get(0).getSize());
        assertEquals("folder", resources.get(1).getType());
        assertEquals("先看  再处理  完成", service.displayText(text));

        String question = service.buildAgentQuestion(text, resources);
        assertTrue(question.indexOf(image.toAbsolutePath().toString())
                < question.indexOf(folder.toAbsolutePath().toString()));
        assertTrue(question.contains("read_resource"));
    }

    @Test
    void rejectsMissingResource(@TempDir Path dir) {
        ResourceRef missing = new ResourceRef();
        missing.setPath(dir.resolve("missing.png").toString());
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class, () -> service.resolve(Arrays.asList(missing)));
        assertTrue(error.getMessage().contains("不存在"));
    }

    @Test
    void allowsMoreThanFiveResources(@TempDir Path dir) throws Exception {
        ResourceRef[] refs = new ResourceRef[6];
        for (int i = 0; i < refs.length; i++) {
            Path file = Files.write(
                    dir.resolve("f" + i + ".txt"),
                    ("text" + i).getBytes(StandardCharsets.UTF_8));
            refs[i] = ref(file, "document");
        }
        List<ResolvedResource> resources = service.resolve(Arrays.asList(refs));
        assertEquals(6, resources.size());
    }

    @Test
    void prefersFilesystemTypeOverStaleMarkerType(@TempDir Path dir) throws Exception {
        // 旧版前端拖拽文件夹只拿到路径，会把标记与 type 都写成 file
        Path folder = Files.createDirectories(dir.resolve("security-audit"));
        String text = "参考 " + marker("file", folder) + " 实现";

        List<ResolvedResource> resources = service.resolve(Arrays.asList(ref(folder, "file")));

        assertEquals("folder", resources.get(0).getType());
        assertTrue(service.buildAgentQuestion(text, resources).contains("[本地资源(folder)"));
    }

    @Test
    void prefersExtensionTypeOverStaleMarkerType(@TempDir Path dir) throws Exception {
        Path image = Files.write(dir.resolve("shot.png"), new byte[] {1, 2, 3});
        String text = "看图 " + marker("file", image);

        List<ResolvedResource> resources = service.resolve(Arrays.asList(ref(image, "file")));

        assertEquals("image", resources.get(0).getType());
        assertTrue(service.buildAgentQuestion(text, resources).contains("[本地资源(image)"));
    }

    @Test
    void appendContentUsesFilesystemTypeInsteadOfStaleReference(@TempDir Path dir) throws Exception {
        Path folder = Files.createDirectories(dir.resolve("security-audit"));

        String content = service.buildAppendContent(
                "继续", Arrays.asList(ref(folder, "file")));

        assertTrue(content.contains("[本地资源(folder)"));
    }

    @Test
    void textFileResolvesToDocumentConsistentWithFrontend(@TempDir Path dir) throws Exception {
        Path notes = Files.write(
                dir.resolve("notes.md"), "# 说明".getBytes(StandardCharsets.UTF_8));

        List<ResolvedResource> resources = service.resolve(Arrays.asList(ref(notes, "file")));

        // 端上统一按 document 展示，后端同口径，避免输入框与历史消息类型不一致
        assertEquals("document", resources.get(0).getType());
    }

    @Test
    void replacesSkillMarkerInPlaceAndKeepsUserOrder(@TempDir Path dir) throws Exception {
        Path skill = Files.createDirectories(dir.resolve("skills/code-audit"));
        Path image = Files.write(dir.resolve("shot.png"), new byte[] {1, 2, 3});
        String text = "先 " + skillMarker("code-audit", skill)
                + " 再看 " + marker("image", image) + " 收尾";

        List<ResolvedResource> resources = service.resolve(Arrays.asList(ref(image, "image")));
        String question = service.buildAgentQuestion(text, resources);

        // Skill 指令留在用户放置的位置，资源标记原位替换，两者互不干扰
        assertTrue(question.contains(
                "[使用 Skill：code-audit（" + skill.toAbsolutePath() + "）] 再看"));
        assertTrue(question.indexOf("使用 Skill") < question.indexOf("[本地资源(image)"));
        // 标记必须被完全消化，不能把原始标识透给模型
        assertTrue(!question.contains("agentforge-skill"));
    }

    @Test
    void displayTextStripsSkillMarkers(@TempDir Path dir) throws Exception {
        Path skill = Files.createDirectories(dir.resolve("skills/code-audit"));

        String visible = service.displayText(skillMarker("code-audit", skill) + " 看下这次改动");

        assertEquals("看下这次改动", visible);
        assertTrue(service.hasSkillMarker("[[agentforge-skill:a:b]]"));
        assertTrue(!service.hasSkillMarker("普通消息"));
    }

    @Test
    void appendContentReplacesSkillMarker(@TempDir Path dir) throws Exception {
        Path skill = Files.createDirectories(dir.resolve("skills/code-audit"));

        String content = service.buildAppendContent("继续 " + skillMarker("code-audit", skill));

        assertTrue(content.contains("[使用 Skill：code-audit（" + skill.toAbsolutePath() + "）]"));
        assertTrue(!content.contains("agentforge-skill"));
    }

    @Test
    void keepsMessageUnchangedWhenNoSkillMarker() {
        // 无 Skill 标记时必须与改造前一致：零回归
        assertEquals("普通消息", service.buildAgentQuestion("普通消息", null));
        assertEquals("普通消息", service.buildAppendContent("普通消息"));
        assertEquals("普通消息", service.displayText("普通消息"));
    }

    private static ResourceRef ref(Path path, String type) {
        ResourceRef ref = new ResourceRef();
        ref.setPath(path.toString());
        ref.setType(type);
        return ref;
    }

    private static String marker(String type, Path path) {
        return "[[agentforge-resource:" + type + ":" + encode(path) + "]]";
    }

    private static String skillMarker(String name, Path path) {
        return "[[agentforge-skill:" + encodeText(name) + ":" + encode(path) + "]]";
    }

    private static String encodeText(String value) {
        try {
            return java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String encode(Path path) {
        try {
            return java.net.URLEncoder.encode(
                    path.toAbsolutePath().toString(), "UTF-8").replace("+", "%20");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}

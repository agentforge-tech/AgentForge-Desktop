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

    private static ResourceRef ref(Path path, String type) {
        ResourceRef ref = new ResourceRef();
        ref.setPath(path.toString());
        ref.setType(type);
        return ref;
    }

    private static String marker(String type, Path path) {
        return "[[agentforge-resource:" + type + ":" + encode(path) + "]]";
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

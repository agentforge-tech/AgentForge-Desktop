package cloud.changlu.agentforge.desktop.modules.skill;

import cloud.changlu.agentforge.desktop.config.DesktopProperties;
import cloud.changlu.agentforge.desktop.modules.skill.api.SkillMetadata;
import cloud.changlu.agentforge.desktop.modules.skill.service.DesktopSkillLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DesktopSkillLoaderTest {
    @TempDir Path temp;

    @Test
    void workspaceSkillOverridesGlobalSkillWithSameName() throws Exception {
        DesktopSkillLoader loader = loader();
        writeSkill(loader.globalRoot().resolve("review"), "review", "global description");
        Path workspace = temp.resolve("project");
        writeSkill(workspace.resolve(".agentforge/skills/review"), "review", "workspace description");

        List<SkillMetadata> skills = loader.listEffectiveSkills(workspace);

        assertEquals(1, skills.size());
        assertEquals("workspace", skills.get(0).getScope());
        assertEquals("workspace description", skills.get(0).getDescription());
        assertTrue(loader.loadSkillIndex(workspace).contains("scope: workspace"));
    }

    @Test
    void detailOnlyReadsConfiguredSkillRoots() throws Exception {
        DesktopSkillLoader loader = loader();
        Path allowed = writeSkill(loader.globalRoot().resolve("safe"), "safe", "safe description");
        Path outside = writeSkill(temp.resolve("outside"), "outside", "outside description");

        assertTrue(loader.skill(null, allowed.toString()).contains("safe description"));
        assertThrows(SecurityException.class, () -> loader.skill(null, outside.toString()));
    }

    @Test
    void importCopiesSourceAndRejectsNameCollision() throws Exception {
        DesktopSkillLoader loader = loader();
        Path source = temp.resolve("source").resolve("writer");
        writeSkill(source, "writer", "write documents");
        Files.write(source.resolve("reference.txt"), "reference".getBytes(StandardCharsets.UTF_8));

        SkillMetadata imported = loader.importGlobalSkill(source);

        assertEquals("writer", imported.getName());
        assertTrue(Files.isRegularFile(loader.globalRoot().resolve("writer/reference.txt")));
        assertTrue(Files.isRegularFile(source.resolve("SKILL.md")));
        assertThrows(IllegalArgumentException.class, () -> loader.importGlobalSkill(source));
        assertFalse(loader.listGlobalSkills().isEmpty());
    }

    @Test
    void importsZipWithSingleTopLevelSkillDirectory() throws Exception {
        DesktopSkillLoader loader = loader();
        Path zip = temp.resolve("dt-zentao.zip");
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(zip))) {
            output.putNextEntry(new ZipEntry("dt-zentao/SKILL.md"));
            output.write("---\nname: dt-zentao\ndescription: 禅道助手\n---\n".getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
            output.putNextEntry(new ZipEntry("dt-zentao/references/guide.md"));
            output.write("guide".getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }

        SkillMetadata imported = loader.importGlobalSkill(zip);

        assertEquals("dt-zentao", imported.getName());
        assertTrue(Files.isRegularFile(loader.globalRoot().resolve("dt-zentao/references/guide.md")));
        assertTrue(Files.isRegularFile(zip));
    }

    @Test
    void parsesYamlBlockDescriptionWithChompingIndicator() throws Exception {
        DesktopSkillLoader loader = loader();
        Path directory = loader.globalRoot().resolve("open-reach-skill");
        Files.createDirectories(directory);
        Files.write(directory.resolve("SKILL.md"), ("---\n"
                + "name: openreach\n"
                + "description: >-\n"
                + "  第一行描述。\n"
                + "  第二行描述。\n"
                + "---\n").getBytes(StandardCharsets.UTF_8));

        SkillMetadata skill = loader.listGlobalSkills().get(0);

        assertEquals("第一行描述。 第二行描述。", skill.getDescription());
    }

    private DesktopSkillLoader loader() {
        DesktopProperties properties = new DesktopProperties();
        properties.setRootDir(temp.resolve("desktop-home").toString());
        return new DesktopSkillLoader(properties);
    }

    private static Path writeSkill(Path directory, String name, String description) throws Exception {
        Files.createDirectories(directory);
        Path file = directory.resolve("SKILL.md");
        String text = "---\nname: " + name + "\ndescription: " + description + "\n---\n\n# " + name;
        Files.write(file, text.getBytes(StandardCharsets.UTF_8));
        return file;
    }
}

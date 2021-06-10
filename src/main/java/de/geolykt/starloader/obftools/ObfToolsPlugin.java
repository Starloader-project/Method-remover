package de.geolykt.starloader.obftools;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.zip.ZipEntry;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.zeroturnaround.zip.ZipUtil;
import org.zeroturnaround.zip.transform.ByteArrayZipEntryTransformer;
import org.zeroturnaround.zip.transform.ZipEntryTransformer;
import org.zeroturnaround.zip.transform.ZipEntryTransformerEntry;

import net.fabricmc.accesswidener.AccessWidener;
import net.fabricmc.accesswidener.AccessWidenerReader;
import net.fabricmc.accesswidener.AccessWidenerVisitor;
import net.fabricmc.stitch.commands.CommandGenerateIntermediary;

public class ObfToolsPlugin implements Plugin<Project> {

    public static final String FOLDER = "build/obftools/";
    public static final String INTERMEDIARY_JAR = FOLDER + "intermediary.jar";
    public static final String INTERMEDIARY_MAP = FOLDER + "slintermediary.tiny";

    @Override
    public void apply(Project gradleProject) {
        ObftoolsExtension extension = gradleProject.getExtensions().create("obfTools", ObftoolsExtension.class);
        PostprocessTask transformerTask = gradleProject.getTasks().create("postprocess", PostprocessTask.class,
                extension);
        gradleProject.afterEvaluate(project -> {
            if (extension.accessWidener != null) {
                doAW(extension, gradleProject);
            }
            Task jarTask = project.getTasks().getByName("jar");
            transformerTask.dependsOn(jarTask);
            if (extension.affectedJar != null) {
                File f = gradleProject.file(extension.affectedJar);
                File map = gradleProject.file(INTERMEDIARY_MAP);
                if (!f.exists()) {
                    throw new RuntimeException("Could not find the specified jar which should be at "
                            + f.getAbsolutePath());
                }
                if (map.exists()) {
                    // Already computed, no need to do it
                    return;
                }
                map.getParentFile().mkdirs();
                CommandGenerateIntermediary command = new CommandGenerateIntermediary();
                String[] additionallOpts = "-i galimulator -p \"^snoddasmannen\\/galimulator\\/(.+\\/)*[a-zA-Z]{1,2}$\" -t \"snoddasmannen/galimulator/\" --keep-package"
                        .split(" ");
                String[] args = new String[additionallOpts.length + 2];
                System.arraycopy(additionallOpts, 0, args, 2, additionallOpts.length);
                args[0] = f.getAbsolutePath();
                args[1] = map.getAbsolutePath();
                try {
                    command.run(args);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
                net.fabricmc.tinyremapper.Main.main(new String[] { f.getAbsolutePath(), gradleProject.file(INTERMEDIARY_JAR).getAbsolutePath(),
                        map.getAbsolutePath(), "official", "intermediary" });
            }
        });
    }

    public void doAW(ObftoolsExtension extension, Project project) {
        File accessWidenerFile = project.file(extension.accessWidener);
        if (!accessWidenerFile.exists()) {
            throw new RuntimeException("Could not find the specified access widener file which should be at "
                    + accessWidenerFile.getAbsolutePath());
        }

        File affectedFile = project.file(extension.affectedJar);
        if (!affectedFile.exists()) {
            throw new RuntimeException("Affected file was not found: " + affectedFile.getAbsolutePath());
        }

        AccessWidener accessWidener = new AccessWidener();
        AccessWidenerReader accessWidenerReader = new AccessWidenerReader(accessWidener);

        try (BufferedReader reader = new BufferedReader(new FileReader(accessWidenerFile))) {
            accessWidenerReader.read(reader);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read project access widener file");
        }
        if (accessWidener.getTargets().isEmpty()) {
            return;
        }
        ZipEntryTransformerEntry[] transformers = accessWidener.getTargets().stream()
                .map(string -> new ZipEntryTransformerEntry(string.replaceAll("\\.", "/") + ".class", getTransformer(accessWidener)))
                .toArray(ZipEntryTransformerEntry[]::new);
        ZipUtil.transformEntries(affectedFile, transformers);
    }

    private ZipEntryTransformer getTransformer(AccessWidener accessWidener) {
        return new ByteArrayZipEntryTransformer() {
            @Override
            protected byte[] transform(ZipEntry zipEntry, byte[] input) {
                ClassReader reader = new ClassReader(input);
                ClassWriter writer = new ClassWriter(0);
                ClassVisitor classVisitor = AccessWidenerVisitor.createClassVisitor(Opcodes.ASM9, writer,
                        accessWidener);
                reader.accept(classVisitor, 0);
                return writer.toByteArray();
            }
        };
    }
}

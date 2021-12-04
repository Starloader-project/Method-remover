package de.geolykt.starloader.obftools;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.jar.JarFile;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.objectweb.asm.tree.ClassNode;

import de.geolykt.starloader.obftools.asm.IntermediaryGenerator;
import de.geolykt.starloader.obftools.asm.Oaktree;
import de.geolykt.starloader.obftools.asm.access.AccessTransformInfo;
import de.geolykt.starloader.obftools.asm.access.AccessWidenerReader;

public class ObfToolsPlugin implements Plugin<Project> {

    public static final String FOLDER = "build/obftools/";
    public static final String INTERMEDIARY_JAR = FOLDER + "intermediary.jar";
    public static final String INTERMEDIARY_MAP = FOLDER + "slintermediary.tiny";

    @Override
    public void apply(Project gradleProject) {
        @SuppressWarnings("null")
        ObftoolsExtension extension = gradleProject.getExtensions().create("obfTools", ObftoolsExtension.class);

        @SuppressWarnings("null")
        PostprocessTask transformerTask = gradleProject.getTasks().create("postprocess", PostprocessTask.class, extension);

        gradleProject.afterEvaluate(project -> {
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
                File intermediaryJar = gradleProject.file(INTERMEDIARY_JAR);
                Oaktree deobfuscator = new Oaktree();
                try {
                    JarFile jar = new JarFile(f);
                    deobfuscator.index(jar);
                    jar.close();
                    deobfuscator.fixInnerClasses();
                    deobfuscator.fixParameterLVT();
                    deobfuscator.guessFieldGenerics();
                    deobfuscator.fixSwitchMaps(true);
                    deobfuscator.fixForeachOnArray(true);
                    deobfuscator.fixComparators(true, true);

                    IntermediaryGenerator generator = new IntermediaryGenerator(map, null, deobfuscator.getClassNodesDirectly());
                    generator.useAlternateClassNaming(extension.alternateNaming != null && extension.alternateNaming == true);
                    generator.addResources(f);
                    generator.remapClassesV2();
                    generator.doProposeEnumFieldsV2();
                    generator.remapGetters();
                    generator.deobfuscate();

                    if (extension.accessWidener != null) {
                        File accessWidenerFile = project.file(extension.accessWidener);
                        if (!accessWidenerFile.exists()) {
                            throw new RuntimeException("Could not find the specified access widener file which should be at "
                                    + accessWidenerFile.getAbsolutePath());
                        }
                        AccessTransformInfo atInfo = new AccessTransformInfo();

                        try (FileInputStream fis = new FileInputStream(accessWidenerFile)) {
                            try (AccessWidenerReader awr = new AccessWidenerReader(atInfo, fis)) {
                                awr.readHeader();
                                while (awr.readLn());
                            }
                        }

                        Map<String, ClassNode> classNodes = new HashMap<>();
                        for (ClassNode node : deobfuscator.getClassNodesDirectly()) {
                            classNodes.put(node.name, node);
                        }
                        atInfo.apply(classNodes, System.err::println);
                    }

                    try (FileOutputStream fos = new FileOutputStream(intermediaryJar)) {
                        deobfuscator.write(fos);
                    }
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            }
        });
    }
}

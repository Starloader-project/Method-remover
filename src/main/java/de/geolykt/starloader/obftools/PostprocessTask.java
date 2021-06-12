package de.geolykt.starloader.obftools;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import javax.inject.Inject;

import org.gradle.api.GradleException;
import org.gradle.api.internal.file.copy.CopyAction;
import org.gradle.api.internal.file.copy.CopyActionProcessingStream;
import org.gradle.api.tasks.WorkResult;
import org.gradle.api.tasks.WorkResults;
import org.gradle.jvm.tasks.Jar;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.zeroturnaround.zip.ZipUtil;
import org.zeroturnaround.zip.transform.ByteArrayZipEntryTransformer;
import org.zeroturnaround.zip.transform.ZipEntryTransformer;

import de.geolykt.starloader.obftools.asm.Oaktree;

import cuchaz.enigma.command.DeobfuscateCommand;

public class PostprocessTask extends Jar {

    private final ObftoolsExtension extension;

    @Inject
    public PostprocessTask(final ObftoolsExtension extension) {
        super();
        this.extension = extension;
    }

    @Override
    protected CopyAction createCopyAction() {
        File source = getArchiveFile().get().getAsFile();
        File temp = new File(source.getParentFile(), source.getName() + ".temp");
        File map = new File(source.getParentFile().getParentFile().getParentFile(), ObfToolsPlugin.INTERMEDIARY_MAP);
        return new TransformedCopyTask(extension.annotation, temp, source, source, map);
    }
} class TransformedCopyTask implements CopyAction {

    private final String annotation;
    private final File src;
    private final File targetTemp;
    private final File targetFinal;
    private final File mapLocation;

    public TransformedCopyTask(String annotation, File targetTemp, File targetFinal, File source, File mapLocation) {
        this.annotation = annotation;
        this.targetTemp = targetTemp;
        this.targetFinal = targetFinal;
        this.src = source;
        this.mapLocation = mapLocation;
    }

    @Override
    public WorkResult execute(CopyActionProcessingStream stream) {
        try (FileOutputStream fos = new FileOutputStream(targetTemp)) {
            transform(annotation, src, fos);
        } catch (IOException e) {
            e.printStackTrace();
            return WorkResults.didWork(false);
        }/*
        net.fabricmc.tinyremapper.Main.main(new String[] {
                targetTemp.getAbsolutePath(), // srcJar
                targetFinal.getAbsolutePath(), // outJar
                mapLocation.getAbsolutePath(), // mappingsFile
//                "intermediary",
//                "named"
              "official",
              "intermediary"
        });*/
        try {
            new DeobfuscateCommand().run(
                    targetTemp.getAbsolutePath(), // input
                    targetFinal.getAbsolutePath(), // output
                    mapLocation.getAbsolutePath()); // map
        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException(e);
        }
        Oaktree deobfuscator = new Oaktree();
        try {
            JarFile jar = new JarFile(targetFinal);
            deobfuscator.index(jar);
            jar.close();
            deobfuscator.fixInnerClasses();
            FileOutputStream fos = new FileOutputStream(targetFinal);
            deobfuscator.write(fos);
            fos.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
        targetTemp.delete();
        return WorkResults.didWork(true);
    }

    private static void transform(String annot, File zip, OutputStream out) {
        ZipOutputStream outputStream = new ZipOutputStream(new BufferedOutputStream(out));
        ZipEntryTransformer transformer = getTransformer(annot);
        ZipUtil.iterate(zip, (input, zipEntry) -> transformer.transform(input, zipEntry, outputStream));
        try {
            outputStream.flush();
            outputStream.close();
        } catch (Exception e) {
            try {
                out.close();
            } catch (Exception e2) {
                e2.printStackTrace();
            }
            throw new GradleException("Could not transform zip!", e);
        }
    }

    private static ZipEntryTransformer getTransformer(String annotation) {
        return new ByteArrayZipEntryTransformer() {
            @Override
            protected byte[] transform(ZipEntry zipEntry, byte[] input) {
                if (annotation != null && zipEntry.getName().endsWith(".class")) {
                    // TODO in some really strange circumstances there will be attempts at having .class files
                    // that are not valid, we might need to intercept those as they may crash ASM
                    ClassReader reader = new ClassReader(input);
                    ClassWriter writer = new ClassWriter(0);
                    ClassVisitor visitor = new ClassremoverVisitor(Opcodes.ASM9, writer, annotation, input);
                    reader.accept(visitor, 0);
                    return writer.toByteArray();
                }
                return input; // Not a valid class file
            }
        };
    }
}

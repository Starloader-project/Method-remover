package de.geolykt.starloader.methodremover;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
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

public class MethodRemovalTask extends Jar {

    private final RemoverGradleExtension extension;

    @Inject
    public MethodRemovalTask(final RemoverGradleExtension extension) {
        super();
        this.extension = extension;
    }

    @Override
    protected CopyAction createCopyAction() {
        File source = getArchiveFile().get().getAsFile();
        File temp = new File(source.getParentFile(), source.getName() + ".temp");
        return new MethodRemoverCopyAction(extension.annotation, temp, source, source);
    }
} class MethodRemoverCopyAction implements CopyAction {

    private final String annotation;
    private final File src;
    private final File targetTemp;
    private final File targetFinal;

    public MethodRemoverCopyAction(String annotation, File targetTemp, File targetFinal, File source) {
        this.annotation = annotation;
        this.targetTemp = targetTemp;
        this.targetFinal = targetFinal;
        this.src = source;
    }

    @Override
    public WorkResult execute(CopyActionProcessingStream stream) {
        try (FileOutputStream fos = new FileOutputStream(targetTemp)) {
            transform(annotation, src, fos);
        } catch (IOException e) {
            e.printStackTrace();
            return WorkResults.didWork(false);
        }
        try {
            FileInputStream fis = new FileInputStream(targetTemp);
            FileOutputStream fos = new FileOutputStream(targetFinal);
            fis.transferTo(fos);
            fos.flush();
            fis.close();
            fos.close();
        } catch (IOException e) {
            e.printStackTrace();
            return WorkResults.didWork(false);
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
                if (zipEntry.getName().endsWith(".class")) {
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

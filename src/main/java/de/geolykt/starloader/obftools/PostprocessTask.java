package de.geolykt.starloader.obftools;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Enumeration;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
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
import cuchaz.enigma.command.InvertMappingsCommand;

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

    private final Collection<Resource> resources = new ArrayList<>();

    public TransformedCopyTask(String annotation, File targetTemp, File targetFinal, File source, File mapLocation) {
        this.annotation = annotation;
        this.targetTemp = targetTemp;
        this.targetFinal = targetFinal;
        this.src = source;
        this.mapLocation = mapLocation;
    }

    @Override
    public WorkResult execute(CopyActionProcessingStream stream) {
        try {
            JarFile jarFile = new JarFile(src);
            Enumeration<JarEntry> entries = jarFile.entries();
            while (entries.hasMoreElements()) {
                JarEntry e = entries.nextElement();
                if (!e.getRealName().endsWith(".class")) {
                    InputStream is = jarFile.getInputStream(e);
                    resources.add(new Resource(is.readAllBytes(), e.getRealName()));
                    is.close();
                }
            }
            jarFile.close();
        } catch (IOException e) {
            e.printStackTrace();
            throw new RuntimeException(e);
        }

        try (FileOutputStream fos = new FileOutputStream(targetTemp)) {
            transform(annotation, src, fos); // Apply method-remover
        } catch (IOException e) {
            e.printStackTrace();
            return WorkResults.didWork(false);
        }

        // We first need to invert the mapping
        File invertedMap = new File(mapLocation.getParentFile(), mapLocation.getName() + ".inverted");
        try {
            if (!invertedMap.exists()) {
                invertMap(mapLocation, invertedMap, false);
            }
            new DeobfuscateCommand().run(
                    targetTemp.getAbsolutePath(), // input
                    targetFinal.getAbsolutePath(), // output
                    invertedMap.getAbsolutePath()); // map
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
            deobfuscator.fixParameterLVT();
            deobfuscator.guessFieldGenerics();
            FileOutputStream fos = new FileOutputStream(targetTemp);
            deobfuscator.write(fos);
            fos.close();
        } catch (IOException e) {
            e.printStackTrace();
        }

        // Copy cached resources back into the jar
        try {
            JarFile jarFile = new JarFile(targetTemp);
            JarOutputStream out = new JarOutputStream(new FileOutputStream(targetFinal));
            Enumeration<JarEntry> entries = jarFile.entries();
            while (entries.hasMoreElements()) {
                JarEntry e = entries.nextElement();
                out.putNextEntry(e);
                InputStream is = jarFile.getInputStream(e);
                is.transferTo(out);
                is.close();
                out.closeEntry();
            }
            jarFile.close();
            for (Resource resource : resources) {
                out.putNextEntry(new JarEntry(resource.getPath()));
                out.write(resource.getData());
                out.closeEntry();
            }
            out.close();
        } catch (IOException e) {
            e.printStackTrace();
            throw new RuntimeException(e);
        }
        targetTemp.delete();
        return WorkResults.didWork(true);
    }

    /**
     * since {@link InvertMappingsCommand} does not support  writing into the tiny format.
     * The input map should be in the tiny format and the output map will be in the in the tiny format.
     * If the output file already exists it will be appended if append is true.
     * Note: if append is true, then the header might not get written!
     *
     * @param sourceMap The original input map
     * @param invertedMap The output map that is inverted
     * @param append Whether to append the output file, otherwise it will overwrite it
     */
    private void invertMap(File sourceMap, File invertedMap, boolean append) {
        try {
            BufferedReader br = new BufferedReader(new FileReader(sourceMap));
            if (br.readLine() == null) {
                br.close();
                return;
            }
            if (!invertedMap.exists()) {
                append = false;
            }
            BufferedWriter bw = new BufferedWriter(new FileWriter(invertedMap, append));
            if (!append) {
                bw.write("v1\tofficial\tintermediary\n");
            }
            String line = br.readLine();
            while (line != null) {
                String[] ln = line.split("\t");
                String oldLine = line;
                line = br.readLine();
                if (ln.length < 3) {
                    bw.newLine();
                    continue;
                }
                if (oldLine.charAt(0) == '#') {
                    bw.write(oldLine);
                    bw.newLine();
                    continue;
                }
                for (int i = 0; i < ln.length - 2; i++) {
                    bw.write(ln[i]);
                    bw.write('\t');
                }
                bw.write(ln[ln.length - 1]);
                bw.write('\t');
                bw.write(ln[ln.length - 2]);
                bw.newLine();
            }
            br.close();
            bw.close();
        } catch (IOException e) {
            e.printStackTrace();
            throw new RuntimeException(e);
        }
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
} final class Resource {

    private final byte[] data;
    private final String path;

    public Resource(byte[] data, String path) {
        this.data = data;
        this.path = path;
    }

    public String getPath() {
        return path;
    }

    public byte[] getData() {
        return data;
    }
}

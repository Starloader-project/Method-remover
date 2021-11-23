package de.geolykt.starloader.obftools.asm.access;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;

import org.objectweb.asm.Opcodes;

import de.geolykt.starloader.obftools.asm.access.AccessFlagModifier.Type;

public class AccessWidenerReader implements AutoCloseable {

    public static class IllegalHeaderException extends IOException {

        /**
         * serialVersionUID.
         */
        private static final long serialVersionUID = 2674486982880918940L;

        public IllegalHeaderException(String message) {
            super(message);
        }
    }

    private final AccessTransformInfo atInfo;
    private final BufferedReader br;

    public AccessWidenerReader(AccessTransformInfo atInfo, InputStream stream) {
        this.br = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
        this.atInfo = atInfo;
    }

    public void readHeader() throws IllegalHeaderException, IOException {
        for (String ln = br.readLine(); ln != null; ln = br.readLine()) {
            int indexOfCommentSymbol = ln.indexOf('#');
            String pureLine = indexOfCommentSymbol == -1 ? ln : ln.substring(0, indexOfCommentSymbol);
            if (!pureLine.isBlank()) {
                String[] blocks = pureLine.trim().split("\\s+");
                if (blocks.length != 3) {
                    throw new IllegalHeaderException(
                            "Header must be in the format of \"accessWidener v2 intermediary\"");
                }
                if (!blocks[0].equalsIgnoreCase("accessWidener")) {
                    throw new IllegalHeaderException(
                            "Header must be in the format of \"accessWidener v2 intermediary\"");
                }
                if (!(blocks[1].equals("v1") || blocks[1].equals("v2"))) {
                    throw new IllegalHeaderException("Cannot read version: " + blocks[1]);
                }
                if (!blocks[2].equals("intermediary")) {
                    throw new UnsupportedOperationException(
                            "As of know only the intermediary namespace is supported for access wideners.");
                }
                return; // Checks passed. Do not fall back to the throw below
            }
        }
        throw new IllegalHeaderException("Unable to find header.");
    }

    public boolean readLn() throws IOException {
        String ln = br.readLine();
        if (ln == null) {
            return false;
        }
        int indexOfCommentSymbol = ln.indexOf('#');
        String pureLine = indexOfCommentSymbol == -1 ? ln : ln.substring(0, indexOfCommentSymbol);
        if (pureLine.isBlank()) {
            return true;
        }
        String[] blocks = pureLine.trim().split("\\s+");
        if (blocks.length != 3 && blocks.length != 5) {
            throw new IOException("Illegal block count. Got " + blocks.length + " expected 3 or 5. Line: " + pureLine);
        }

        String targetClass = blocks[2].replace('.', '/');
        String operation = blocks[0];
        String typeName = blocks[1];

        Optional<String> name;
        Optional<String> desc;
        Type memberType = null;
        switch (typeName.toLowerCase(Locale.ROOT)) {
        case "class":
            if (blocks.length != 3) {
                throw new IOException("Illegal block count. Got " + blocks.length
                        + " but expected 3 due to the CLASS modifier. Line: " + pureLine);
            }
            memberType = Type.CLASS;
            name = Optional.empty();
            desc = Optional.empty();
            break;
        case "field":
            memberType = Type.FIELD;
            // Fall-through intended
        case "method":
            if (memberType == null) {
                memberType = Type.METHOD;
            }
            if (blocks.length != 5) {
                throw new IOException("Illegal block count. Got " + blocks.length
                        + " but expected 5 due to the METHOD or FIELD modifier. Line: " + pureLine);
            }
            name = Optional.of(blocks[3]);
            desc = Optional.of(blocks[4]);
            break;
        default:
            throw new IOException();
        }

        AccessFlagModifier modifier;

        switch (operation.toLowerCase(Locale.ROOT)) {
        case "accessible":
            modifier = new AccessFlagModifier.AccessibleModifier(memberType, targetClass, name, desc);
            break;
        case "extendable":
            modifier = new AccessFlagModifier.ExtendableModifier(memberType, targetClass, name, desc);
            break;
        case "mutable":
            modifier = new AccessFlagModifier.RemoveFlagModifier(memberType, targetClass, name, desc, Opcodes.ACC_FINAL, "mutable");
            break;
        case "natural":
            modifier = new AccessFlagModifier.RemoveFlagModifier(memberType, targetClass, name, desc, Opcodes.ACC_SYNTHETIC, "natural");
            break;
        case "denumerised":
            modifier = new AccessFlagModifier.RemoveFlagModifier(memberType, targetClass, name, desc, Opcodes.ACC_ENUM, "denumerised");
            break;
        default:
            throw new UnsupportedOperationException("Unknown mode: " + operation);
        }

        atInfo.modifiers.add(modifier);
        return true;
    }

    @Override
    public void close() throws IOException {
        br.close();
    }
}

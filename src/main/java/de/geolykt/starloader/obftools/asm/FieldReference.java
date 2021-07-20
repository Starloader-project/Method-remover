package de.geolykt.starloader.obftools.asm;

import java.util.Locale;
import java.util.Objects;

import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;

/**
 * A reference to a field within a class.
 * This class is similar to {@link FieldNode} / {@link FieldInsnNode} though with a few
 * "useless" features removed so it can be used for one thing only.
 */
public class FieldReference {

    private final String desc;
    private final String name;
    private final String owner;

    public FieldReference(FieldInsnNode instruction) {
        this.owner = instruction.owner;
        this.name = instruction.name;
        this.desc = instruction.desc;
    }

    public FieldReference(String owner, FieldNode node) {
        this.owner = owner;
        this.name = node.name;
        this.desc = node.desc;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this) {
            return true;
        }
        if (!(obj instanceof FieldReference)) {
            return false;
        }
        FieldReference other = (FieldReference) obj;
        return other.name.equals(this.name) && other.desc.equals(this.desc) && other.owner.equals(this.owner);
    }

    public String getName() {
        return name;
    }

    public String getOwner() {
        return owner;
    }

    @Override
    public int hashCode() {
        return Objects.hash(owner, name, desc);
    }

    @Override
    public String toString() {
        return String.format(Locale.ROOT, "FieldReference[owner=\"%s\",name=\"%s\",desc=\"%s\"]", this.owner, this.name, this.desc);
    }
}

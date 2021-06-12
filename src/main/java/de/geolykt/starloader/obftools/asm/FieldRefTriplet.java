package de.geolykt.starloader.obftools.asm;

import java.util.Locale;
import java.util.Objects;

import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;

public class FieldRefTriplet {
    public final String name;
    public final String owner;
    public final String desc;

    public FieldRefTriplet(String name, String owner, String desc) {
        this.name = name;
        this.owner = owner;
        this.desc = desc;
    }

    public FieldRefTriplet(FieldInsnNode node) {
        this(node.name, node.owner, node.desc);
    }

    public FieldRefTriplet(FieldNode node, String owner) {
        this(node.name, owner, node.desc);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, owner, desc);
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof FieldRefTriplet)) {
            return false;
        }
        FieldRefTriplet var = (FieldRefTriplet) obj;
        return var.name.equals(name) && var.owner.equals(owner) && var.desc.equals(desc);
    }

    @Override
    public String toString() {
        return String.format(Locale.ROOT, "FieldRefTriplet[name=%s, owner=%s, desc=%s]", name, owner, desc);
    }
}

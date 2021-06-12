package de.geolykt.starloader.obftools.asm;

import java.util.Objects;

import org.objectweb.asm.tree.FieldNode;

public class SignatureNode {

    protected String generic;
    public final String type;

    public SignatureNode(final String type, String generics) {
        this.generic = generics;
        this.type = type;
    }

    public SignatureNode(FieldNode field) {
        this.generic = field.signature;
        this.type = field.desc;
    }

    public void setGenerics(String generics) {
        this.generic = generics;
    }

    public void setGenerics(SignatureNode node) {
        if (node == null) {
            this.generic = null;
        } else {
            this.generic = node.toString();
        }
    }

    @Override
    public String toString() {
        if (generic == null) {
            return null;
        }
        return String.format("%s<%s>;", type, generic);
    }

    @Override
    public int hashCode() {
        return Objects.hash(generic, type);
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof SignatureNode)) {
            return false;
        }
        SignatureNode n = (SignatureNode) obj;
        return n.type.equals(type) && ((generic == null && n.generic == null) || (generic != null && generic.equals(n.generic)));
    }
}

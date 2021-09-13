package de.geolykt.starloader.obftools.asm.remapper;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import org.objectweb.asm.tree.MethodNode;

final class MethodReference {
    private final String desc;
    private final String name;
    private final String owner;

    public MethodReference(String owner, MethodNode node) {
        this(owner, node.desc, node.name);
    }

    public MethodReference(String owner, String desc, String name) {
        this.owner = owner;
        this.name = name;
        this.desc = desc;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this) {
            return true;
        }
        if (!(obj instanceof MethodReference)) {
            return false;
        }
        MethodReference other = (MethodReference) obj;
        return other.name.equals(this.name) && other.desc.equals(this.desc) && other.owner.equals(this.owner);
    }

    /**
     * Obtains the method descriptor of the referred method
     *
     * @return The method descriptor
     */
    public String getDesc() {
        return desc;
    }

    public String getName() {
        return name;
    }

    /**
     * Obtains the internal name of the owner.
     * This means it will be like this: "org/example/ClassName"
     *
     * @return The internal name of the owner
     */
    public String getOwner() {
        return owner;
    }

    @Override
    public int hashCode() {
//        return (owner.hashCode() & 0xFFFF0000 | name.hashCode() & 0x0000FFFF) ^ desc.hashCode();
        return Objects.hash(owner, name, desc);
    }

    @Override
    public String toString() {
        return String.format(Locale.ROOT, "MethodReference[owner=\"%s\",name=\"%s\",desc=\"%s\"]", this.owner, this.name, this.desc);
    }
} public class MethodRenameMap {

    private final Map<MethodReference, String> renames = new HashMap<>();

    public MethodRenameMap() {
    }

    public void clear() {
        renames.clear();
    }

    public String get(String owner, String descriptor, String oldName) {
        return renames.get(new MethodReference(owner, descriptor, oldName));
    }

    public String getOrDefault(String owner, String descriptor, String oldName, String defaultValue) {
        return renames.getOrDefault(new MethodReference(owner, descriptor, oldName), defaultValue);
    }

    public String optGet(String owner, String descriptor, String oldName) {
        return renames.getOrDefault(new MethodReference(owner, descriptor, oldName), oldName);
    }

    public void put(String owner, String descriptor, String name, String newName) {
        renames.put(new MethodReference(owner, descriptor, name), newName);
    }

    public int size() {
        return renames.size();
    }
}

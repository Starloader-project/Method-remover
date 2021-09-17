package de.geolykt.starloader.obftools.asm.remapper;

import java.util.List;

import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;

public interface IRemapper {

    /**
     * Note: due to the circumstances of how some remappers work, this method call may be not required as the remapper
     * may remap the input ClassNodes without cloning the list (and ClassNodes) in any capacity.
     *
     * @return Returns the targets
     */
    public List<ClassNode> getOutput();

    /**
     * Inserts a field renaming entry to the remapping list.
     * The owner and desc strings must be valid for the current class names, i. e. without {@link #remapClassName(String, String)}
     * and {@link #process()} applied. The fields are not actually renamed until {@link #process()} is invoked.
     * These tasks are however removed as soon as {@link #process()} is invoked.
     *
     * @param owner The internal name of the current owner of the field
     * @param desc The descriptor string of the field entry
     * @param oldName The old name of the field
     * @param newName The new name of the field
     * @see Type#getInternalName()
     */
    public void remapField(String owner, String desc, String oldName, String newName);

    /**
     * Inserts a method renaming entry to the remapping list.
     * The owner and desc strings must be valid for the current class names, i. e. without {@link #remapClassName(String, String)}
     * and {@link #process()} applied. The method are not actually renamed until {@link #process()} is invoked.
     *<p>
     * <b>WARNING (only affects some implementations - read the implNote of your implementation): if the method is non-static and non-private and the owning class non-final then it is recommended that the change is
     * propagated through the entire tree. Renaming a method will only affect one class, not multiple - which may void
     * overrides or other similar behaviours.</b>
     *
     * @param owner The internal name of the current owner of the method
     * @param desc The descriptor string of the method entry
     * @param oldName The old name of the method
     * @param newName The new name of the method
     * @see Type#getInternalName()
     * @throws If a mapping error occurs.
     */
    public void remapMethod(String owner, String desc, String oldName, String newName) throws ConflicitingMappingException;

    /**
     * Removes a method remapping entry from the method remapping list. This method practically undoes {@link #remapMethod(String, String, String, String)}
     * Note that some implementations may also remap the method that was also overridden in child classes while other
     * implementations will only remap the provided method in the provided class and nowhere other.
     *
     * @param owner The class of the method that should not be remapped
     * @param desc The descriptor of the method to not remap
     * @param name The name of the method that should not be remapped
     */
    public void removeMethodRemap(String owner, String desc, String name);
}

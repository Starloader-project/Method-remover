package de.geolykt.starloader.obftools.asm;

/**
 * Utility for dissecting a descriptor string.
 */
public class DescString {

    private final String desc;
    private int startIndex = 0;

    public DescString(String desc) {
        int begin = 1; // Always starts with a paranthesis
        int end = desc.indexOf(')');
        this.desc = desc.substring(begin, end);
    }

    public String nextType() {
        char type = desc.charAt(startIndex);
        if (type == 'L') {
            // Object-type type
            // the description ends with a semicolon here, which has to be kept
            int endPos = desc.indexOf(';', startIndex) + 1;
            String ret = desc.substring(startIndex, endPos);
            startIndex = endPos;
            return ret;
        } else {
            // Primitive-type type
            startIndex++; // Increment index by one, since the size of the type is exactly one
            return Character.toString(type);
        }
    }

    public void reset() {
        startIndex = 0;
    }
}

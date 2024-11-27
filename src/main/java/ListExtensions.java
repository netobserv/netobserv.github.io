import java.util.*;

import io.quarkus.qute.TemplateExtension;

@TemplateExtension(namespace = "lists")
public class ListExtensions {
    static <T> List<T> shuffle(Collection<T> c) {
        List<T> l = new ArrayList(c);
        Collections.shuffle(l);
        return l;
    }
}

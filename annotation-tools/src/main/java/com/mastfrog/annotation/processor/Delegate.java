package com.mastfrog.annotation.processor;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import com.mastfrog.java.vogon.ClassBuilder;
import com.mastfrog.annotation.AnnotationUtils;
import com.mastfrog.function.throwing.io.IOBiConsumer;
import com.mastfrog.util.strings.Strings;

/**
 *
 * @author Tim Boudreau
 */
public abstract class Delegate {

    protected ProcessingEnvironment processingEnv;
    private AnnotationUtils utils;
    private IOBiConsumer<ClassBuilder<String>, Element[]> classWriter;
    private final boolean deferClassBuilders;
    private final Map<String, ClassBuilder<String>> classBuilders = new HashMap<>();
    private Set<ClassBuilderEntry> deferredClassBuilders;
    private Delegates delegates;

    protected Delegate() {
        this(false);
    }

    protected Delegate(boolean deferClassBuildersToEndOfRound) {
        this.deferClassBuilders = deferClassBuildersToEndOfRound;
        if (deferClassBuilders) {
            deferredClassBuilders = new HashSet<>();
        } else {
            deferredClassBuilders = null;
        }
    }

    protected void classBuilder(Object pkg, Object className, ClassBuilderConsumer c) throws Exception {
        String pkgName = pkg == null ? null : pkg.toString();
        String cl = className.toString();
        String fqn = pkgName == null ? cl : pkgName + "." + cl;
        ClassBuilder<String> result = classBuilders.get(fqn);
        boolean existing = result != null;
        if (!existing) {
            result = ClassBuilder.forPackage(pkg).named(cl);
            classBuilders.put(fqn, result);
        }
        fqn = result.fqn();
        cl = result.className();
        pkgName = result.packageName();
        c.run(result, existing, pkgName, cl, fqn);
    }

    @FunctionalInterface
    public interface ClassBuilderConsumer {

        public void run(ClassBuilder<String> str, boolean existing, String pkg, String className, String fqn);
    }

    protected final void log(String msg) {
        utils().log(msg);
    }

    protected final void log(String msg, Object... args) {
        utils().log(msg, args);
    }

    public void logException(Throwable thrown, boolean fail) {
        utils().logException(thrown, fail);
    }

    protected static <T> Key<T> key(Class<T> type, String name) {
        return new Key<>(type, name);
    }

    final void init(ProcessingEnvironment env, AnnotationUtils utils,
            IOBiConsumer<ClassBuilder<String>, Element[]> classWriter, Delegates delegates) {
        this.processingEnv = env;
        this.delegates = delegates;
        this.utils = utils;
        this.classWriter = classWriter;
        onInit(env, utils);
    }

    protected <T> T share(Key<T> key, T data) {
        delegates.putSharedData(key, data);
        return data;
    }

    protected <T> Set<? extends T> getAll(Key<T> key) {
        return delegates.getSharedData(key);
    }

    protected <T> Optional<T> get(Key<T> key) {
        return delegates.getOneShared(key);
    }

    protected final void writeOne(ClassBuilder<String> bldr, Element... elements) throws IOException {
        if (deferClassBuilders) {
            findOrAddClassBuilder(bldr, elements);
        } else {
            classWriter.accept(bldr, elements);
        }
    }

    protected void onInit(ProcessingEnvironment env, AnnotationUtils utils) {
    }

    protected final AnnotationUtils utils() {
        if (utils == null) {
            throw new IllegalStateException("Call to get utils before initialization");
        }
        return utils;
    }

    protected final ProcessingEnvironment env() {
        return processingEnv;
    }

    boolean _roundCompleted(Map<AnnotationMirror, Element> processed, RoundEnvironment roundEnv) throws Exception {
        boolean result = onRoundCompleted(processed, roundEnv);
        if (this.deferClassBuilders) {
            saveClassBuilders();
        }
        return result;
    }

    protected boolean onRoundCompleted(Map<AnnotationMirror, Element> processed, RoundEnvironment roundEnv) throws Exception {
        return true;
    }

    protected boolean validateAnnotationMirror(AnnotationMirror mirror, ElementKind kind, Element element) {
        return true;
    }

    protected boolean processConstructorAnnotation(ExecutableElement constructor, AnnotationMirror mirror, RoundEnvironment roundEnv) throws Exception {
        throw new IllegalStateException("Annotation not applicable to constructors or not implemented for them " + mirror.getAnnotationType() + " or processConstructorAnnotation should be overridden but is not.");
    }

    protected boolean processMethodAnnotation(ExecutableElement method, AnnotationMirror mirror, RoundEnvironment roundEnv) throws Exception {
        throw new IllegalStateException("Annotation not applicable to methods or not implemented for them " + mirror.getAnnotationType() + " or processMethodAnnotation should be overridden but is not.");
    }

    protected boolean processFieldAnnotation(VariableElement var, AnnotationMirror mirror, RoundEnvironment roundEnv) throws Exception {
        throw new IllegalStateException("Annotation not applicable to fields or not implemented for them " + mirror.getAnnotationType() + " or processFieldAnnotation should be overridden but is not.");
    }

    protected boolean processTypeAnnotation(TypeElement type, AnnotationMirror mirror, RoundEnvironment roundEnv) throws Exception {
        throw new IllegalStateException("Annotation not applicable to classes or not implemented for them " + mirror.getAnnotationType() + " or processTypeAnnotation should be overridden but is not.");
    }

    @Override
    public boolean equals(Object o) {
        return o != null && o.getClass() == getClass();
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }

    private void findOrAddClassBuilder(ClassBuilder<String> bldr, Element... elements) {
        assert deferredClassBuilders != null;
        for (ClassBuilderEntry cbe : this.deferredClassBuilders) {
            if (cbe.builder == bldr) {
                cbe.addElements(elements);
                return;
            }
        }
        ClassBuilderEntry e = new ClassBuilderEntry(bldr, elements);
        deferredClassBuilders.add(e);
    }

    @SuppressWarnings("UseSpecificCatch")
    private void saveClassBuilders() throws Exception {
        Exception ex = null;
        Set<ClassBuilder<String>> written = new HashSet<>();
        for (Iterator<ClassBuilderEntry> it = deferredClassBuilders.iterator(); it.hasNext();) {
            ClassBuilderEntry cbe = it.next();
            try {
                cbe.write(classWriter);
                written.add(cbe.builder);
            } catch (Exception e) {
                if (ex == null) {
                    ex = e;
                } else {
                    ex.addSuppressed(e);
                }
            } finally {
                it.remove();
            }
        }
        if (ex != null) {
            throw ex;
        }
        Set<ClassBuilder<String>> created = new HashSet<>(this.classBuilders.values());
        created.removeAll(written);
        if (!created.isEmpty()) {
            StringBuilder sb = new StringBuilder("Some builders which were created were not built: ");
            Strings.concatenate(", ", created, sb, ClassBuilder::fqn);
            utils().warn(sb.toString());
        }
    }

    static final class ClassBuilderEntry {

        private final ClassBuilder<String> builder;
        private final Set<Element> elements = new HashSet<>();

        public ClassBuilderEntry(ClassBuilder<String> builder, Element... elements) {
            this.builder = builder;
            this.elements.addAll(Arrays.asList(elements));
        }

        void write(IOBiConsumer<ClassBuilder<String>, Element[]> classWriter) throws IOException {
            classWriter.accept(builder, elements.toArray(new Element[elements.size()]));
        }

        void addElements(Element... els) {
            elements.addAll(Arrays.asList(els));
        }

        @Override
        public int hashCode() {
            int hash = 3;
            hash = 17 * hash + Objects.hashCode(this.builder);
            return hash;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj == null) {
                return false;
            }
            if (getClass() != obj.getClass()) {
                return false;
            }
            final ClassBuilderEntry other = (ClassBuilderEntry) obj;
            if (!Objects.equals(this.builder, other.builder)) {
                return false;
            }
            return true;
        }
    }
}

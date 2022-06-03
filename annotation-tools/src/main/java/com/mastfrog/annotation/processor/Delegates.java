package com.mastfrog.annotation.processor;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import static javax.lang.model.element.ElementKind.CLASS;
import static javax.lang.model.element.ElementKind.CONSTRUCTOR;
import static javax.lang.model.element.ElementKind.FIELD;
import static javax.lang.model.element.ElementKind.INTERFACE;
import static javax.lang.model.element.ElementKind.METHOD;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import com.mastfrog.java.vogon.ClassBuilder;
import com.mastfrog.annotation.AnnotationUtils;
import com.mastfrog.function.throwing.io.IOBiConsumer;

/**
 *
 * @author Tim Boudreau
 */
public class Delegates {

    protected final boolean layerGenerating;
    private final Map<String, Set<DelegateEntry>> delegates = new LinkedHashMap<>();

    private final Map<Key<?>, Set<?>> sharedData = new HashMap<>();

    protected Delegates(boolean layerGenerating) {
        this.layerGenerating = layerGenerating;
    }

    @SuppressWarnings("unchecked")
    public <T> void putSharedData(Key<T> key, T data) {
        assert data != null;
        Set<T> s = (Set<T>) sharedData.get(key);
        if (s == null) {
            s = new LinkedHashSet<>();
            sharedData.put(key, s);
        }
        s.add(key.type().cast(data));
    }

    @SuppressWarnings("unchecked")
    public <T> Set<T> getSharedData(Key<T> key) {
        return (Set<T>) sharedData.getOrDefault(key, Collections.emptySet());
    }

    public <T> Optional<T> getOneShared(Key<T> key) {
        Set<T> shared = getSharedData(key);
        return shared.isEmpty() ? Optional.empty() : Optional.of(shared.iterator().next());
    }

    public Set<String> supportedAnnotationTypes() {
        return new LinkedHashSet<>(delegates.keySet());
    }

    @Override
    public String toString() {
        if (delegates.isEmpty()) {
            return "{}";
        }
        StringBuilder sb = new StringBuilder();
        sb.append('{');
        for (Map.Entry<String, Set<DelegateEntry>> e : delegates.entrySet()) {
            sb.append("\n    ").append(e.getKey() + ": " + e.getValue());
        }
        return sb.append('}').toString();
    }

    private void addDelegate(String type, DelegateEntry en) {
        Set<DelegateEntry> entries = delegates.get(type);
        if (entries == null) {
            entries = new LinkedHashSet<>(5);
            delegates.put(type, entries);
        }
        entries.add(en);
    }

    public MidAddDelegate apply(Delegate delegate) {
//        if (!layerGenerating && delegate instanceof LayerGeneratingDelegate) {
//            throw new IllegalArgumentException("Cannot add a layer generating "
//                    + "delegate to a non-layer-generating annotation "
//                    + "processor");
//        }
        return new MidAddDelegate(this, delegate);
    }

    public Set<Delegate> allDelegates() {
        Set<Delegate> result = new LinkedHashSet<>();
        for (Map.Entry<String, Set<DelegateEntry>> e : delegates.entrySet()) {
            Set<DelegateEntry> entries = e.getValue();
            for (DelegateEntry d : entries) {
                result.add(d.delegate);
            }
        }
        return result;
    }

    public void init(ProcessingEnvironment env, AnnotationUtils utils, IOBiConsumer<ClassBuilder<String>, Element[]> classWriter) {
//        init(env, utils, classWriter, null, null);
        Set<Delegate> all = allDelegates();
        for (Delegate d : all) {
            d.init(env, utils, classWriter, this);
        }
    }

    /*
    public void init(ProcessingEnvironment env, AnnotationUtils utils, IOBiConsumer<ClassBuilder<String>, Element[]> classWriter, Function<Element[], LayerBuilder> layerBuilderFetcher, BiConsumer<LayerTask, Element[]> layerTaskAdder) {
        Set<Delegate> all = allDelegates();
        for (Delegate d : all) {
            if (d instanceof LayerGeneratingDelegate) {
                ((LayerGeneratingDelegate) d).init(env, utils, classWriter, layerBuilderFetcher, layerTaskAdder, this);
            } else {
                d.init(env, utils, classWriter, this);
            }
        }
    }
    */

    public boolean validateAnnotationMirror(AnnotationMirror mirror, ElementKind kind, Element element) {
        String type = mirror.getAnnotationType().toString();
        Set<DelegateEntry> entries = this.delegates.get(type);
        boolean result = true;
        if (entries != null) {
            for (DelegateEntry e : entries) {
                result &= e.delegate.validateAnnotationMirror(mirror, kind, element);
            }
        }
        return result;
    }

    private Set<DelegateEntry> entriesFor(Element el, AnnotationMirror mirror) {
        Set<DelegateEntry> result = new LinkedHashSet<>(5);
        Set<DelegateEntry> found = this.delegates.get(mirror.getAnnotationType().toString());
        if (found != null) {
            for (DelegateEntry de : found) {
                if (de.matches(mirror, el.getKind())) {
                    result.add(de);
                }
            }
        }
        return result;
    }

    public boolean processConstructorAnnotation(ExecutableElement constructor, AnnotationMirror mirror, RoundEnvironment roundEnv, Set<? super Delegate> delegates) throws Exception {
        boolean result = true;
        for (DelegateEntry de : entriesFor(constructor, mirror)) {
            Delegate del = de.delegate;
            delegates.add(del);
            result &= del.utils().withLogContext(del.getClass().getName(), () -> {
                return del.processConstructorAnnotation(constructor, mirror, roundEnv);
            });
        }
        return result;
    }

    public boolean processMethodAnnotation(ExecutableElement method, AnnotationMirror mirror, RoundEnvironment roundEnv, Set<? super Delegate> delegates) throws Exception {
        boolean result = true;
        for (DelegateEntry de : entriesFor(method, mirror)) {
            Delegate del = de.delegate;
            delegates.add(del);
            result &= del.utils().withLogContext(del.getClass().getName(), () -> {
                return del.processMethodAnnotation(method, mirror, roundEnv);
            });
        }
        return result;
    }

    public boolean processFieldAnnotation(VariableElement var, AnnotationMirror mirror, RoundEnvironment roundEnv, Set<? super Delegate> delegates) throws Exception {
        boolean result = true;
        for (DelegateEntry de : entriesFor(var, mirror)) {
            Delegate del = de.delegate;
            delegates.add(del);
            result &= del.utils().withLogContext(del.getClass().getName(), () -> {
                return del.processFieldAnnotation(var, mirror, roundEnv);
            });
        }
        return result;
    }

    public boolean processTypeAnnotation(TypeElement type, AnnotationMirror mirror, RoundEnvironment roundEnv, Set<? super Delegate> delegates) throws Exception {
        boolean result = true;
        for (DelegateEntry de : entriesFor(type, mirror)) {
            Delegate del = de.delegate;
            delegates.add(del);
            result &= del.utils().withLogContext(del.getClass().getName(), () -> {
                return del.processTypeAnnotation(type, mirror, roundEnv);
            });
        }
        return result;
    }

    public boolean onRoundCompleted(Map<AnnotationMirror, Element> processed, RoundEnvironment roundEnv, Set<Delegate> used) throws Exception {
        boolean result = true;
        for (Delegate d : used) {
            result &= d._roundCompleted(processed, roundEnv);
        }
        return result;
    }

    public static final class MidAddDelegate {

        private final Delegates delegates;
        private final Delegate delegate;
        private static final Set<ElementKind> supportedKinds
                = EnumSet.of(INTERFACE, CLASS, FIELD, METHOD, CONSTRUCTOR);

        public MidAddDelegate(Delegates delegates, Delegate delegate) {
            this.delegates = delegates;
            this.delegate = delegate;
        }

        public FinishAddDelegate to(ElementKind oneKind, ElementKind... moreKinds) {
            Set<ElementKind> kinds = EnumSet.of(oneKind);
            if (moreKinds.length > 0) {
                kinds.addAll(Arrays.asList(moreKinds));
            }
            Set<ElementKind> unsupported = EnumSet.copyOf(kinds);
            unsupported.removeAll(supportedKinds);
            if (!unsupported.isEmpty()) {
                throw new IllegalArgumentException("Supported kinds are "
                        + supportedKinds + " but found " + unsupported);
            }
            return new FinishAddDelegate(delegates, delegate, kinds);
        }
    }

    public static final class FinishAddDelegate {

        private final Delegates delegates;
        private final Delegate delegate;
        private final Set<ElementKind> kinds;

        private FinishAddDelegate(Delegates delegates, Delegate delegate, Set<ElementKind> kinds) {
            this.delegates = delegates;
            this.delegate = delegate;
            this.kinds = kinds;
        }

        private void addOne(String type) {
            DelegateEntry en = new DelegateEntry(delegate, kinds, type);
            delegates.addDelegate(type, en);
        }

        public MidAddDelegate onAnnotationTypesAnd(String firstType, String... moreTypes) {
            whenAnnotationTypes(firstType, moreTypes);
            return new MidAddDelegate(delegates, delegate);
        }

        public Delegates whenAnnotationTypes(String firstType, String... moreTypes) {
            addOne(firstType);
            for (String t : moreTypes) {
                addOne(t);
            }
            return delegates;
        }
    }

    private static final class DelegateEntry {

        private final Delegate delegate;
        private final Set<ElementKind> kinds;
        private final String type;

        public DelegateEntry(Delegate delegate, Set<ElementKind> kinds, String type) {
            this.delegate = delegate;
            this.kinds = kinds;
            this.type = type;
        }

        public boolean matches(AnnotationMirror mirror, ElementKind kind) {
            if (kinds.contains(kind)) {
                return type.equals(mirror.getAnnotationType().toString());
            }
            return false;
        }

        @Override
        public String toString() {
            return delegate.getClass().getSimpleName() + "{" + type + "}<" + kinds.toString() + ">";
        }

        @Override
        public int hashCode() {
            int hash = 5;
            hash = 97 * hash + Objects.hashCode(this.delegate);
            hash = 97 * hash + Objects.hashCode(this.kinds);
            hash = 97 * hash + Objects.hashCode(this.type);
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
            final DelegateEntry other = (DelegateEntry) obj;
            if (!Objects.equals(this.type, other.type)) {
                return false;
            }
            if (!Objects.equals(this.delegate, other.delegate)) {
                return false;
            }
            return Objects.equals(this.kinds, other.kinds);
        }
    }
}

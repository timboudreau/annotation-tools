package com.mastfrog.annotation.nblayer;

import java.io.IOException;
import java.io.OutputStream;
import static java.nio.charset.StandardCharsets.UTF_8;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import com.mastfrog.annotation.AnnotationUtils;
import com.mastfrog.annotation.processor.Delegate;
import com.mastfrog.annotation.processor.Delegates;
import java.util.Set;
import javax.annotation.processing.Filer;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import com.mastfrog.java.vogon.ClassBuilder;
import org.openide.filesystems.annotations.LayerBuilder;
import org.openide.filesystems.annotations.LayerGeneratingProcessor;
import com.mastfrog.annotation.validation.AbstractPredicateBuilder;
import com.mastfrog.util.preconditions.Exceptions;

/**
 *
 * @author Tim Boudreau
 */
public abstract class AbstractLayerGeneratingDelegatingProcessor extends LayerGeneratingProcessor {

    private AnnotationUtils utils;

    private final NbDelegates delegates = new NbDelegates();

    protected AbstractLayerGeneratingDelegatingProcessor() {
    }

    @Override
    public final Set<String> getSupportedAnnotationTypes() {
        // Take care to preserve delegates being run in the order
        // they were added here:
        Set<String> result = new LinkedHashSet<>(delegates.supportedAnnotationTypes());
        for (String other : super.getSupportedAnnotationTypes()) {
            if (!result.contains(other)) {
                result.add(other);
            }
        }
        return result;
    }

    @Override
    public final Set<String> getSupportedOptions() {
        Set<String> result = new LinkedHashSet<>(super.getSupportedOptions());
        result.add(AnnotationUtils.AU_LOG);
        return result;
    }

    public final void logException(Throwable thrown, boolean fail) {
        utils().logException(thrown, fail);
    }

    public final void log(String val) {
        utils().log(val);
    }

    public final void log(String val, Object... args) {
        utils().log(val, args);
    }

    protected void installDelegates(Delegates delegates) {

    }

    @Override
    public synchronized final void init(ProcessingEnvironment processingEnv) {
        utils = new AnnotationUtils(processingEnv, getSupportedAnnotationTypes(), getClass());
        super.init(processingEnv);
        installDelegates(delegates);
        delegates.init(processingEnv, utils, this::writeOne, this::getLayerBuilder, this::addLayerTask);
        onInit(processingEnv, utils);
        used.clear();
//        System.out.println(getClass().getSimpleName() + " DELEGATES: \n" + delegates);
    }

    private LayerBuilder getLayerBuilder(Element... elements) {
        try {
            // With multiple nested delegates, we can wind up trying to
            // *read* the generated layer file twice - so instead we need
            // to cache the first layer builder created, and reuse that
            // until processing is over for this round (at which point the
            // superclass will write it)
            if (cachedLayerBuilder != null) {
                return cachedLayerBuilder;
            }
            return layer(elements);
        } catch (Exception ex) {
            ex.printStackTrace(System.err);
            utils.fail("Exception fetching layer builder");
            return Exceptions.chuck(ex);
        }
    }
    private LayerBuilder cachedLayerBuilder;

    private void discardCachedLayerBuilder() {
        cachedLayerBuilder = null;
    }

    protected void onInit(ProcessingEnvironment env, AnnotationUtils utils) {
    }

    protected final AnnotationUtils utils() {
        if (utils == null) {
            throw new IllegalStateException("Attempt to use utils before "
                    + "init() has been called.");
        }
        return utils;
    }

    protected void onBeforeHandleProcess(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {

    }

    protected void onAfterHandleProcess(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {

    }

    private boolean _validateAnnotationMirror(AnnotationMirror mirror, ElementKind kind, Element element) {
        return AbstractPredicateBuilder.enter(element, mirror, () -> {
            return validateAnnotationMirror(mirror, kind, element) && delegates.validateAnnotationMirror(mirror, kind, element);
        });
    }
    Set<Delegate> used = new HashSet<>();

    @Override
    public final boolean handleProcess(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        onBeforeHandleProcess(annotations, roundEnv);
        boolean done = true;
        Map<AnnotationMirror, Element> elementForAnnotation = new HashMap<>();
//            System.out.println(getClass().getSimpleName() + " ANNOTATIONS ORDER: ");
//            for (String anno : getSupportedAnnotationTypes()) {
//                System.out.println("    " + simpleName(anno));
//            }
        for (String annotationClass : getSupportedAnnotationTypes()) {
            Set<Element> annotated = utils().findAnnotatedElements(roundEnv, Collections.singleton(annotationClass));
//                if (!annotated.isEmpty()) {
//                    System.out.println("  ELEMENTS ORDER FOR " + simpleName(annotationClass) + ": ");
//                    for (Element e : annotated) {
//                        System.out.println("    " + e);
//                    }
//                }
            for (Element el : annotated) {
                AnnotationMirror mirror = utils().findAnnotationMirror(el, annotationClass);
                if (mirror == null) {
                    continue;
                }
                utils().log("Mirror {0} on kind {1} by {2} with {3}", mirror, el.getKind(), getClass().getSimpleName(), delegates);
                if (!_validateAnnotationMirror(mirror, el.getKind(), el)) {
                    continue;
                }
                boolean ok = false;
                switch (el.getKind()) {
                    case CONSTRUCTOR:
                            try {
                        ExecutableElement constructor = (ExecutableElement) el;
                        done &= delegates.processConstructorAnnotation(constructor, mirror, roundEnv, used);
                        done &= processConstructorAnnotation(constructor, mirror, roundEnv);
                        ok = true;
                    } catch (Exception ex) {
                        processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, "IOException processing annotation " + annotationClass, el);
                                ex.printStackTrace(System.err);
                    }
                    break;
                    case METHOD:
                            try {
                        ExecutableElement method = (ExecutableElement) el;
                        done &= delegates.processMethodAnnotation(method, mirror, roundEnv, used);
                        done &= processMethodAnnotation(method, mirror, roundEnv);
                        ok = true;
                    } catch (Exception ex) {
                        processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, "IOException processing annotation " + annotationClass, el);
                                ex.printStackTrace(System.err);
                    }
                    break;
                    case FIELD:
                            try {
                        VariableElement var = (VariableElement) el;
                        done &= delegates.processFieldAnnotation(var, mirror, roundEnv, used);
                        done &= processFieldAnnotation(var, mirror, roundEnv);
                        ok = true;
                    } catch (Exception ex) {
                        processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, "IOException processing annotation " + annotationClass, el);
                                ex.printStackTrace(System.err);
                    }
                    break;
                    case INTERFACE:
                    case CLASS:
                            try {
                        TypeElement type = (TypeElement) el;
                        done &= delegates.processTypeAnnotation(type, mirror, roundEnv, used);
                        done &= processTypeAnnotation(type, mirror, roundEnv);
                        ok = true;
                    } catch (Exception ex) {
                        processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, "IOException processing annotation " + annotationClass, el);
                        ex.printStackTrace();
                    }
                    break;
                    default:
                        processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                                "Not applicable to " + el.getKind() + ": " + mirror.getAnnotationType(), el);
                }
                if (ok) {
                    elementForAnnotation.put(mirror, el);
                }
            }
        }
        try {
            done &= onRoundCompleted(elementForAnnotation, roundEnv);
            done &= delegates.onRoundCompleted(elementForAnnotation, roundEnv, used);
        } catch (Exception ex) {
            utils().logException(ex, true);
            ex.printStackTrace(System.out);
        }
        onAfterHandleProcess(annotations, roundEnv);
        if (done) {
            runLayerTasks(roundEnv);
        }
        if (roundEnv.processingOver()) {
            discardCachedLayerBuilder();
        }
        return done;
    }

    private void runLayerTasks(RoundEnvironment roundEnv) {
        try {
            Set<Element> all = new HashSet<>();
            for (TaskContext task : layerTasks) {
                all.addAll(Arrays.asList(task.elements));
            }
            LayerBuilder b = null;
            for (Iterator<TaskContext> it = layerTasks.iterator(); it.hasNext();) {
                TaskContext task = it.next();
                if (b == null) {
                    // defer failure until we're inside here
                    b = layer(all.toArray(new Element[all.size()]));
                }
                boolean wasRaised = roundEnv.errorRaised();
                try {
                    task.task.run(b);
                    boolean nowRaised = roundEnv.errorRaised();
                    if (nowRaised && !wasRaised) {
                        utils().log("Generation failed in {0}", getClass());
                        break;
                    }
                } catch (Exception ex) {
                    ex.printStackTrace(System.out);
                    utils.fail(task.originatedBy + ": " + ex);
                } finally {
                    it.remove();
                }
            }
        } finally {
            layerTasks.clear();
        }
    }

    private final List<TaskContext> layerTasks = new ArrayList<>();

    /**
     * Add a task to build the layer, which will run on successful processing
     * completion.
     *
     * @param task The task
     * @param elements The elements generating for
     */
    protected void addLayerTask(LayerTask task, Element... elements) {
        utils().log("Add layer task: " + task);
        layerTasks.add(new TaskContext(task, elements, getClass().getName()));
    }

    private static final class TaskContext {

        final LayerTask task;
        final Element[] elements;
        private final String originatedBy;

        public TaskContext(LayerTask task, Element[] elements, String originatedBy) {
            this.task = task;
            this.elements = elements;
            this.originatedBy = originatedBy;
        }

        public String toString() {
            StringBuilder sb = new StringBuilder(originatedBy);
            sb.append("{").append(task).append("}");
            return sb.toString();
        }

    }

    protected boolean onRoundCompleted(Map<AnnotationMirror, Element> processed, RoundEnvironment env) throws Exception {
        return true;
    }

    protected boolean validateAnnotationMirror(AnnotationMirror mirror, ElementKind kind, Element el) {
        return true;
    }

    protected boolean processConstructorAnnotation(ExecutableElement constructor, AnnotationMirror mirror, RoundEnvironment roundEnv) throws Exception {
//        throw new IllegalStateException("Annotation not applicable to constructors or not implemented for them " + mirror.getAnnotationType());
        return true;
    }

    protected boolean processMethodAnnotation(ExecutableElement method, AnnotationMirror mirror, RoundEnvironment roundEnv) throws Exception {
//        throw new IllegalStateException("Annotation not applicable to methods or not implemented for them " + mirror.getAnnotationType());
        return true;
    }

    protected boolean processFieldAnnotation(VariableElement var, AnnotationMirror mirror, RoundEnvironment roundEnv) throws Exception {
//        throw new IllegalStateException("Annotation not applicable to fields or not implemented for them " + mirror.getAnnotationType());
        return true;
    }

    protected boolean processTypeAnnotation(TypeElement type, AnnotationMirror mirror, RoundEnvironment roundEnv) throws Exception {
//        throw new IllegalStateException("Annotation not applicable to classes or not implemented for them " + mirror.getAnnotationType());
        return true;
    }

    protected final void writeOne(ClassBuilder<String> cb, Element... elems) throws IOException {
        Filer filer = processingEnv.getFiler();
        JavaFileObject file = filer.createSourceFile(cb.fqn(), elems);
        try (OutputStream out = file.openOutputStream()) {
            out.write(cb.build().getBytes(UTF_8));
        }
    }
}

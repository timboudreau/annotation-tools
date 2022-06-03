package com.mastfrog.annotation.processor;

import java.io.IOException;
import java.io.OutputStream;
import static java.nio.charset.StandardCharsets.UTF_8;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import com.mastfrog.annotation.AnnotationUtils;
import java.util.Set;
import javax.annotation.processing.AbstractProcessor;
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
import com.mastfrog.annotation.validation.AbstractPredicateBuilder;

/**
 * Base class for annotation processors with a few twists: You can install a set
 * of <code><a href="Delegate.html">Delegate</a></code> instances which handle
 * different annotations - and in particular, run in a specific, deterministic
 * order in which they are added. A communication mechanism is provided through
 * <code><a href="Key.html">Key</a></code>s which can be set and retrieved. This
 * makes it possible to develop processors that handle large amounts of
 * interdependent code, without a resulting mass of spaghetti-code.
 *
 * @author Tim Boudreau
 */
public abstract class AbstractDelegatingProcessor extends AbstractProcessor {

    private AnnotationUtils utils;
    private final Delegates delegates;

    protected AbstractDelegatingProcessor() {
        this.delegates= createDelegates();
    }
    
    protected Delegates createDelegates() {
        return new Delegates(false);
    }

    /**
     * Implement this method to set up your delegates.
     *
     * @param delegates
     */
    protected void installDelegates(Delegates delegates) {

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
        Set<String> result = new HashSet<>(super.getSupportedOptions());
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

    @Override
    public synchronized final void init(ProcessingEnvironment processingEnv) {
        utils = new AnnotationUtils(processingEnv, getSupportedAnnotationTypes(), getClass());
        super.init(processingEnv);
        installDelegates(delegates);
        delegates.init(processingEnv, utils, this::writeOne);
        onInit(processingEnv, utils);
        used.clear();
//        System.out.println(getClass().getSimpleName() + " DELEGATES: \n" + delegates);
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

    private boolean _validateAnnotationMirror(AnnotationMirror mirror, ElementKind kind, Element element) {
        return AbstractPredicateBuilder.enter(element, mirror, () -> {
            return validateAnnotationMirror(mirror, kind, element) && delegates.validateAnnotationMirror(mirror, kind, element);
        });
    }
    Set<Delegate> used = new HashSet<>();

    protected void onBeforeHandleProcess(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {

    }

    protected void onAfterHandleProcess(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {

    }

    @Override
    public final boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        onBeforeHandleProcess(annotations, roundEnv);
        try {
            boolean done = true;
            Map<AnnotationMirror, Element> elementForAnnotation = new HashMap<>();
            for (Element el : utils().findAnnotatedElements(roundEnv, getSupportedAnnotationTypes())) {
                for (String annotationClass : getSupportedAnnotationTypes()) {
                    AnnotationMirror mirror = utils().findAnnotationMirror(el, annotationClass);
                    if (mirror == null) {
                        utils.warn("Could not locate annotation mirror for " + annotationClass
                                + " - not on classpath?"
                                + " Ignoring annotation on " + el, el);
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
                                done &= processConstructorAnnotation(constructor, mirror, roundEnv);
                                done &= delegates.processConstructorAnnotation(constructor, mirror, roundEnv, used);
                                ok = true;
                            } catch (Exception ex) {
                                processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, "IOException processing annotation " + annotationClass, el);
                                ex.printStackTrace();
                            }
                            break;
                        case METHOD:
                            try {
                                ExecutableElement method = (ExecutableElement) el;
                                done &= processMethodAnnotation(method, mirror, roundEnv);
                                done &= delegates.processMethodAnnotation(method, mirror, roundEnv, used);
                                ok = true;
                            } catch (Exception ex) {
                                processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, "IOException processing annotation " + annotationClass, el);
                                ex.printStackTrace();
                            }
                            break;
                        case FIELD:
                            try {
                                VariableElement var = (VariableElement) el;
                                done &= processFieldAnnotation(var, mirror, roundEnv);
                                done &= delegates.processFieldAnnotation(var, mirror, roundEnv, used);
                                ok = true;
                            } catch (Exception ex) {
                                processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, "IOException processing annotation " + annotationClass, el);
                                ex.printStackTrace();
                            }
                            break;
                        case INTERFACE:
                        case CLASS:
                            try {
                                TypeElement type = (TypeElement) el;
                                done &= processTypeAnnotation(type, mirror, roundEnv);
                                done &= delegates.processTypeAnnotation(type, mirror, roundEnv, used);
                                ok = true;
                            } catch (Exception ex) {
                                utils.logException(ex, true);
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
            return false;
//            return done;
        } finally {
            onAfterHandleProcess(annotations, roundEnv);
        }
    }

    protected boolean onRoundCompleted(Map<AnnotationMirror, Element> processed, RoundEnvironment roundEnv) throws Exception {
        return true;
    }

    protected boolean validateAnnotationMirror(AnnotationMirror mirror, ElementKind kind, Element element) {
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

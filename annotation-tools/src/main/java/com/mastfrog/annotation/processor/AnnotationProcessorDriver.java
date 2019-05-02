package com.mastfrog.annotation.processor;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;
import com.mastfrog.annotation.AnnotationUtils;
import static com.mastfrog.annotation.AnnotationUtils.simpleName;

/**
 *
 * @author Tim Boudreau
 */
class AnnotationProcessorDriver {

    interface DriverHandler {
        Collection<String> getSupportedAnnotationTypes();
        boolean validate(String annoType, AnnotationMirror mir, ElementKind kind, Element on, RoundEnvironment roundEnv) throws Exception;
        boolean process(String annoType, AnnotationMirror mir, ElementKind kind, Element on, RoundEnvironment roundEnv) throws Exception;
    }

    interface LoopHandler {

        void onBefore(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv);

        boolean processOne(String annoType, AnnotationMirror mir, ElementKind kind, Element on, RoundEnvironment roundEnv) throws Exception;

        boolean onAfter(Map<AnnotationMirror, Element> all, RoundEnvironment roundEnv, boolean prevResult) throws Exception;
    }

    boolean mainLoop(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv, LoopHandler handler, ProcessingEnvironment processingEnv, AnnotationUtils utils, Delegates delegates, DriverHandler driver) {
        handler.onBefore(annotations, roundEnv);
        boolean done = true;
        Map<AnnotationMirror, Element> elementForAnnotation = new HashMap<>();
        System.out.println(getClass().getSimpleName() + " ANNOTATIONS ORDER: ");
        for (String anno : driver.getSupportedAnnotationTypes()) {
            System.out.println("    " + simpleName(anno));
        }
        for (String annotationClass : driver.getSupportedAnnotationTypes()) {
            Set<Element> annotated = utils.findAnnotatedElements(roundEnv, Collections.singleton(annotationClass));
            if (!annotated.isEmpty()) {
                System.out.println("  ELEMENTS ORDER FOR " + simpleName(annotationClass) + ": ");
                for (Element e : annotated) {
                    System.out.println("    " + e);
                }
            }
            for (Element el : annotated) {
                AnnotationMirror mirror = utils.findAnnotationMirror(el, annotationClass);
                if (mirror == null) {
                    continue;
                }
                utils.log("Mirror {0} on kind {1} by {2} with {3}", mirror, el.getKind(), getClass().getSimpleName(), delegates);
                boolean ok = false;
                try {
                    done &= handler.processOne(annotationClass, mirror, el.getKind(), el, roundEnv);
                    ok = true;
                } catch (Exception e) {
                    utils.logException(e, true);
                    done = false;
                }
                if (ok) {
                    elementForAnnotation.put(mirror, el);
                }
            }
        }
        try {
            done &= handler.onAfter(elementForAnnotation, roundEnv, done);
        } catch (Exception ex) {
            utils.logException(ex, true);
            ex.printStackTrace(System.out);
        }
        return done;
    }
}

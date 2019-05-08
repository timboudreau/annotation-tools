/*
 * The MIT License
 *
 * Copyright 2014 tim.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package com.mastfrog.annotation.registries;

import com.mastfrog.annotation.AnnotationUtils;
import java.util.Set;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic.Kind;

/**
 * An annotation processor that generates an index correctly, waiting until all
 * rounds of annotation processing have been completed before writing the output
 * so as to avoid a FilerException on opening a file for writing twice in one
 * compile sequence.
 *
 * @author Tim Boudreau
 */
public abstract class IndexGeneratingProcessor<T extends IndexEntry> extends AbstractProcessor {

    private final boolean processOnFinalRound;
    protected final AnnotationIndexFactory<T> indexer;
    protected ProcessingEnvironment processingEnv;
    private AnnotationUtils utils;
    private int roundIndex;

    protected IndexGeneratingProcessor(AnnotationIndexFactory<T> indexer) {
        this(false, indexer);
    }

    protected IndexGeneratingProcessor(boolean processOnFinalRound, AnnotationIndexFactory<T> indexer) {
        this.processOnFinalRound = processOnFinalRound;
        this.indexer = indexer;
    }

    /**
     * Get the AnnotationUtils associated with this environment.
     *
     * @return An AnnotationUtils
     * @throws IllegalStateException if called before javac has initialized
     * this processor (e.g. from the constructor).
     */
    protected final AnnotationUtils utils() {
        if (utils == null) {
            throw new IllegalStateException("Cannot call utils() before "
                    + "javac has called AnnotationProcessor.init()");
        }
        return utils;
    }

    @Override
    public synchronized final void init(ProcessingEnvironment processingEnv) {
        utils = new AnnotationUtils(processingEnv, getSupportedAnnotationTypes(), getClass());
        this.processingEnv = processingEnv;
        super.init(processingEnv);
        onInit(processingEnv, utils);
    }

    /**
     * Called from init() for any initialization tasks. If you are using
     * the predicates AnnotationUtils can generate with its builders to test
     * annotation values or annotated elements for validity, this is a good
     * place to create the predicates you will use to test those things.
     *
     * @param processingEnv The environment
     * @param utils The annotation utils
     */
    protected void onInit(ProcessingEnvironment processingEnv, AnnotationUtils utils) {
        // do nothing
    }

    /**
     * Called before one round of annotation processing.
     * The default implementation is empty.
     *
     * @param env The environment
     * @param processingOver If this is the final round
     * @param roundIndex The index of the round
     */
    protected void onBeforeRound(RoundEnvironment env, boolean processingOver, int roundIndex) {
        // do nothing
    }

    /**
     * Called after one round of annotation processing.
     * The default implementation is empty.
     *
     * @param env The environment
     * @param processingOver If this is the final round
     * @param roundIndex The index of the round
     */
    protected void onAfterRound(RoundEnvironment env, boolean processingOver, int roundIndex) {
        // do nothing
    }

    /**
     * Called after the final round of annotation processing completes.
     * The default implementation is empty.
     */
    protected void onDone() {
        // do nothing
    }

    @Override
    public final boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        if (roundEnv.errorRaised()) {
            return false;
        }
        boolean over = roundEnv.processingOver();
        try {
            onBeforeRound(roundEnv, over, roundIndex);
            if (over) {
                if (processOnFinalRound) {
                    handleProcess(annotations, roundEnv, utils);
                }
                indexer.write(processingEnv);
                onDone();
                return true;
            } else {
                return handleProcess(annotations, roundEnv, utils);
            }
        } catch (Exception e) {
            if (processingEnv != null) {
                processingEnv.getMessager().printMessage(Kind.ERROR, e.toString());
            }
            e.printStackTrace(System.err);
            return false;
        } finally {
            roundIndex++;
            onAfterRound(roundEnv, over, roundIndex - 1);
        }
    }

    /**
     * Implement annotation processing here.
     *
     * @param annotations The set of annotations
     * @param roundEnv The environment
     * @return what to return from the process method - if true, the annotations
     * processed here will <i>not</i> be processed by others.
     */
    protected abstract boolean handleProcess(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv, AnnotationUtils utils);

    /**
     * Add an element to the index file associated with the passed path.
     *
     * @param path A path within the output JAR
     * @param l The line element type associated with this processor
     * @param el The elements associated with this line
     * @return true if the index will be different as a result of this call
     */
    protected final boolean addIndexElement(String path, T l, Element... el) {
        return indexer.add(path, l, processingEnv, el);
    }
}

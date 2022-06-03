/*
 * The MIT License
 *
 * Copyright 2022 Mastfrog Technologies.
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
package com.mastfrog.annotation.nblayer;

import com.mastfrog.annotation.AnnotationUtils;
import com.mastfrog.annotation.processor.Delegate;
import com.mastfrog.annotation.processor.Delegates;
import com.mastfrog.function.throwing.io.IOBiConsumer;
import com.mastfrog.java.vogon.ClassBuilder;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;
import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.Element;
import org.openide.filesystems.annotations.LayerBuilder;

/**
 *
 * @author Tim Boudreau
 */
public class NbDelegates extends Delegates {
    
    public NbDelegates() {
        super(true);
    }

    @Override
    public void init(ProcessingEnvironment env, AnnotationUtils utils,
            IOBiConsumer<ClassBuilder<String>, Element[]> classWriter) {
        init(env, utils, classWriter, null, null);
    }

    public MidAddDelegate apply(Delegate delegate) {
        if (!layerGenerating && delegate instanceof LayerGeneratingDelegate) {
            throw new IllegalArgumentException("Cannot add a layer generating "
                    + "delegate to a non-layer-generating annotation "
                    + "processor");
        }
        return new MidAddDelegate(this, delegate);
    }
    
    
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

}

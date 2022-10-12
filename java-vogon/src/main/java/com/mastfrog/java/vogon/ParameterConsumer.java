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
package com.mastfrog.java.vogon;

import com.mastfrog.java.vogon.ClassBuilder.AnnotatedArgumentBuilder;
import com.mastfrog.java.vogon.ClassBuilder.AnnotationBuilder;
import com.mastfrog.java.vogon.ClassBuilder.MultiAnnotatedArgumentBuilder;
import com.mastfrog.java.vogon.ClassBuilder.ParameterNameBuilder;
import com.mastfrog.java.vogon.ClassBuilder.TypeNameBuilder;
import java.util.function.Consumer;

/**
 * Abstraction for things that can have parameters added to them - methods,
 * constructors, lambdas. This interface, along with ArgumentConsumer, and
 * CodeBlockOwner, makes it possible to write plugin-frameworks that can have
 * contributors which can decorate either a constructor or a factory method
 * with parameters and annotations, without needing to know which type of
 * thing it is they are decorating.
 *
 * @author Tim Boudreau
 */
public interface ParameterConsumer<B> {

    /**
     * Add an argument that has a single annotation.
     *
     * @param annotationType The annotation type name
     * @return An annotation builder
     */
    AnnotationBuilder<AnnotatedArgumentBuilder<ParameterNameBuilder<B>>> addAnnotatedArgument(String annotationType);

    /**
     * Add an argument that has a single annotation, using a consumer to specify
     * the annotation (closeAnnotation() does not need to be called by the
     * consumer).
     *
     * @param annotationType An annotation type
     * @param c A consumer
     * @return in most impementations, this
     */
    B addAnnotatedArgument(String annotationType, Consumer<AnnotationBuilder<AnnotatedArgumentBuilder<ParameterNameBuilder<Void>>>> c);

    /**
     * Add an un-annotated argument to this ParameterConsumer.
     *
     * @param type The type name
     * @param name The argument name
     * @return in most implementations, this
     */
    B addArgument(String type, String name);

    /**
     * Add the first of potentially many annotations to an argument, then
     * specifying the argument.
     *
     * @param annotationType The annotation type
     * @return An annotation builder for fleshing out the initial annotation
     */
    AnnotationBuilder<MultiAnnotatedArgumentBuilder<ParameterNameBuilder<TypeNameBuilder<B>>>> addMultiAnnotatedArgument(String annotationType);

    /**
     * Create a builder for adding an argument that may have an arbirary number
     * of annotations - this method (without the initial annotation type) is
     * useful in code generators where plug-ins may participate in contributing
     * parameter annotations without knowing about each other.
     *
     * @return A builder
     */
    MultiAnnotatedArgumentBuilder<ParameterNameBuilder<TypeNameBuilder<B>>> addMultiAnnotatedArgument();

    /**
     * Create a builder for adding an argument that may have an arbirary number
     * of annotations - this method (without the initial annotation type) is
     * useful in code generators where plug-ins may participate in contributing
     * parameter annotations without knowing about each other, completing that
     * builder within the scope of the passed consumer.
     *
     * @return A builder
     */
    B addMultiAnnotatedArgument(String annotationType, Consumer<AnnotationBuilder<MultiAnnotatedArgumentBuilder<ParameterNameBuilder<TypeNameBuilder<Void>>>>> c);

}

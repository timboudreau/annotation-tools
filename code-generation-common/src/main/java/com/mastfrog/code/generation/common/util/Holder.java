/*
 * The MIT License
 *
 * Copyright 2019 Mastfrog Technologies.
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
package com.mastfrog.code.generation.common.util;

import com.mastfrog.code.generation.common.error.IncompleteSourceElementException;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * A utility class useful for implementing the lambda-builder pattern - used to
 * test that the builder passed to the lambda was really built before the
 * original outer method returns. Depending on whether a conclusion can be
 * inferred, the code may call a close method to complete the nested builder for
 * the user, or may throw an exception if the builder must be completed with
 * information that cannot be inferred to generate valid code. This avoids the
 * surprise of populating a builder incompletely and winding up with code that
 * is simply missing because the builder was never completed.
 * <p>
 * The typical usage pattern looks like:
 * </p>
 * <pre>
 * public OuterBuilderType&lt;T*gt; someSubBuilder(String name, Consumer&lt;? super SubBuilder&lt;?&gt;&gt c) {
 *     Holder&lt;OuterBuilderType&gt; hold = new Holder&lt;&gt;();
 *     SubBuilder&lt;?&gt; result = new SubBuilder&lt;Void&gt;(name, bldr -> {
 *         this.theSubBuilder = bldr;
 *         hold.set(this);
 *         return null;
 *     });
 *     c.accept(result);
 *     // We have now exited the builder.  If it is incomplete, we need either to complete it,
 *     // or fail - the worst possible outcome is to silently drop whatever code was run because
 *     // the function we passed to SubBuilder was never called.
 * 
 *     // CHOICES:
 *     // 1. If SubBuilder is an open-ended builder, like a code-block, that can have
 *     // any number of elements added to it, and the user must explicitly close it, do that
 *     // for them
 *     hold.ifUnset(result::close);
 *     return hold.get();
 *
 *     // 2. If SubBuilder has a terminal element that MUST be set by the user and cannot be inferred:
 *     // This will throw if the builder was not completed
 *     return hold.get("The cadwalleder was not added to the whatzit");
 *
 *     // 3. If the SubBuilder has a terminal element, but there is some reasonable default
 *     // that you want to set in that case:
 *     hold.ifUnset(() -> result.on("this"));
 *     return hold.get();
 * }
 *
 * </pre>
 *
 * @author Tim Boudreau
 */
public final class Holder<T> implements Consumer<T>, Supplier<T> {

    private T obj;

    private boolean wasSetCalled;

    /**
     * Implementation of consumer.
     *
     * @param obj An argument to set
     */
    public void accept(T obj) {
        set(obj);
    }

    /**
     * Set the object.
     *
     * @param <O> The object subtype
     * @param obj The object
     */
    public <O extends T> void set(O obj) {
        wasSetCalled = true;
        this.obj = obj;
    }

    /**
     * Returns true, either if the object is not null, or set was called, but with null
     * (some builders except null as a way to generate a null value, so this is valid).
     *
     * @return true if set was called with any value at all
     */
    public boolean isSet() {
        return obj != null || wasSetCalled;
    }

    /**
     * Run some code only if set was never called.
     *
     * @param run A runnable
     */
    public void ifUnset(Runnable run) {
        if (!isSet()) {
            run.run();
        }
    }

    /**
     * Get the value, calling the passed runnable if set was never called.
     *
     * @param ifNull A runnable
     * @return the value or nunll
     */
    public T get(Runnable ifNull) {
        if (!isSet()) {
            ifNull.run();
        }
        return obj;
    }

    /**
     * Get the value, throwing an exception with the passed method if unset.
     *
     * @param nullExceptionMessage
     * @return
     */
    public T get(String nullExceptionMessage) {
        if (!isSet()) {
            throw new IncompleteSourceElementException(nullExceptionMessage);
        }
        return obj;
    }

    /**
     * Get the value, throwing an exception with the passed method if unset.
     *
     * @param nullExceptionMessage
     * @return
     */
    public T get(Class<?> builderType) {
        if (!isSet()) {
            throw new IncompleteSourceElementException(builderType);
        }
        return obj;
    }


    public T get(Supplier<String> nullExceptionMessage) {
        if (!isSet()) {
            throw new IncompleteSourceElementException(nullExceptionMessage.get());
        }
        return obj;
    }

    public T get() {
        return obj;
    }
}

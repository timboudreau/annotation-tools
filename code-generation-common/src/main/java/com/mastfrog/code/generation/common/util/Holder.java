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
 *
 * @author Tim Boudreau
 */
public final class Holder<T> implements Consumer<T>, Supplier<T> {

    private T obj;

    private boolean wasSetCalled;

    public void accept(T obj) {
        set(obj);
    }

    public <O extends T> void set(O obj) {
        wasSetCalled = true;
        this.obj = obj;
    }

    public boolean isSet() {
        return obj != null || wasSetCalled;
    }

    public void ifUnset(Runnable run) {
        if (!isSet()) {
            run.run();
        }
    }

    public T get(Runnable ifNull) {
        if (!isSet()) {
            ifNull.run();
        }
        return obj;
    }

    public T get(String nullExceptionMessage) {
        if (!isSet()) {
            throw new IllegalStateException(nullExceptionMessage);
        }
        return obj;
    }

    public T get(Supplier<String> nullExceptionMessage) {
        if (!isSet()) {
            throw new IllegalStateException(nullExceptionMessage.get());
        }
        return obj;
    }

    public T get() {
        return obj;
    }
}

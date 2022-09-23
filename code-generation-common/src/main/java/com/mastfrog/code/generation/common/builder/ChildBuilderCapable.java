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
package com.mastfrog.code.generation.common.builder;

import com.mastfrog.code.generation.common.BodyBuilder;
import com.mastfrog.code.generation.common.LinesBuilder;
import com.mastfrog.code.generation.common.util.Holder;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 *
 * @author Tim Boudreau
 */
abstract class ChildBuilderCapable<T, J extends ChildBuilderCapable<T, J>> implements BodyBuilder {

    protected final Function<? super J, ? extends T> converter;

    protected ChildBuilderCapable(Function<? super J, ? extends T> converter) {
        this.converter = converter;
    }

    @SuppressWarnings("unchecked")
    protected final J cast() {
        return (J) this;
    }

    protected <B extends BodyBuilder, R extends BodyBuilder> R subBuilder(
            Function<Function<? super B, ? extends T>, ? extends B> func,
            Function<? super B, ? extends R> onBuilt,
            Consumer<? super B> c) {
        Holder<R> hold = new Holder<>();
        B target = func.apply(bldr -> {
            hold.set(onBuilt.apply(bldr));
            return null;
        });
        c.accept(target);
        return hold.get(target.getClass().getSimpleName() + " not completed.");
    }

    protected <B extends BodyBuilder> T closeableBuilder(
            Function<Function<? super B, ? extends T>, ? extends B> func,
            Consumer<? super B> onBuilt,
            Consumer<? super B> c) {
        Holder<T> hold = new Holder<>();
        B target = func.apply(bldr -> {
            onBuilt.accept(bldr);
            hold.set(build());
            return null;
        });
        c.accept(target);
        hold.ifUnset(() -> {
            if (target instanceof ClosableBuilder<?>) {
                ((ClosableBuilder<?>) target).close();
            }
        });
        return hold.get(target.getClass().getSimpleName() + " not completed.");
    }

    protected void validate() {
        // do nothing
    }

    protected T build() {
        validate();
        return converter.apply(cast());
    }

    @Override
    public String toString() {
        LinesBuilder lb = new LinesBuilder();
        buildInto(lb);
        return lb.toString();
    }

}

package com.mastfrog.java.vogon;

import java.util.function.Supplier;

/**
 *
 * @author Tim Boudreau
 */
interface BodyBuilder {

    void buildInto(LinesBuilder lines);

    public static BodyBuilder lazy(Supplier<BodyBuilder> s) {
        return lb -> {
            s.get().buildInto(lb);
        };
    }
}

package com.mastfrog.annotation.validation;

import com.mastfrog.predicates.NamedPredicate;
import java.util.function.BiPredicate;
import java.util.function.Supplier;
import javax.lang.model.type.TypeMirror;
import com.mastfrog.annotation.AnnotationUtils;
import static com.mastfrog.annotation.AnnotationUtils.simpleName;

/**
 *
 * @author Tim Boudreau
 */
abstract class TypeMirrorComparer implements BiPredicate<TypeMirror, TypeMirror> {

    private final AnnotationUtils utils;

    TypeMirrorComparer(AnnotationUtils utils) {
        this.utils = utils;
    }

    boolean isErasure() {
        return false;
    }

    public TypeMirrorComparer erasure() {
        return new TypeMirrorComparer(utils) {
            @Override
            public boolean test(TypeMirror t, TypeMirror u) {
                return TypeMirrorComparer.this.test(t == null ? null : utils.erasureOf(t),
                        u == null ? null : utils.erasureOf(u));
            }

            @Override
            boolean isErasure() {
                return true;
            }
        };
    }

    public TypeMirrorComparer reverse() {
        return new TypeMirrorComparer(utils) {
            @Override
            public boolean test(TypeMirror t, TypeMirror u) {
                return TypeMirrorComparer.this.test(u, t);
            }
        };
    }

    public NamedPredicate<TypeMirror> toPredicate(TypeMirror t) {
        return new NamedPredicate<TypeMirror>() {
            @Override
            public boolean test(TypeMirror o) {
                return TypeMirrorComparer.this.test(t, o);
            }

            @Override
            public String name() {
                return (isErasure() ? "erasure-" : "") + "is-" 
                        + (t == null ? "null" : simpleName(t.toString()));
            }
        };
    }

    public NamedPredicate<TypeMirror> toPredicate(String typeName) {
        return new NamedPredicate<TypeMirror>() {
            @Override
            public boolean test(TypeMirror o) {
                return TypeMirrorComparer.this.test(utils.type(typeName), o);
            }

            @Override
            public String toString() {
                return name();
            }

            @Override
            public String name() {
                return (isErasure() ? "erasure-" : "") + "is-" + simpleName(typeName);
            }
        };
    }

    public NamedPredicate<TypeMirror> toPredicate(Supplier<TypeMirror> t) {
        return new NamedPredicate<TypeMirror>() {
            @Override
            public boolean test(TypeMirror o) {
                return TypeMirrorComparer.this.test(t.get(), o);
            }

            @Override
            public String toString() {
                return name();
            }

            @Override
            public String name() {
                return (isErasure() ? "erasure-" : "") + "is-" + t;
            }
        };
    }

}

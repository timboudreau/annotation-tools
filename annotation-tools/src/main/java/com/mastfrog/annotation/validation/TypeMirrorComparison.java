package com.mastfrog.annotation.validation;

import com.mastfrog.predicates.AbsenceAction;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import javax.lang.model.type.ExecutableType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Types;
import com.mastfrog.annotation.AnnotationUtils;

/**
 *
 * @author Tim Boudreau
 */
enum TypeMirrorComparison {
    EXACT_MATCH, SAME_TYPE, IS_SUBTYPE, IS_SUPERTYPE, IS_ASSIGNABLE,
    IS_SUBSIGNATURE, IS_SUPERSIGNATURE;

    private <V> Supplier<TypeMirror> lazyTypeMirrorSupplier(V v, Function<V, TypeMirror> convert, BiFunction<Boolean, String, Boolean> b) {
        return () -> {
            TypeMirror result = convert.apply(v);
            b.apply(result != null, "No type element found for " + v);
            return result;
        };
    }

    <V> Predicate<TypeMirror> predicate(V v, Function<V, TypeMirror> convert, AnnotationUtils utils, BiFunction<Boolean, String, Boolean> b, AbsenceAction onNotFound) {
        // Do NOT convert ahead of time, or we can fail compilation on
        // creating a type test if we are simply on the classpath
        // of a project that does not have the type we're resolving
        // on the classpath
        return predicate(lazyTypeMirrorSupplier(v, convert, b), utils, b);
    }

    Predicate<TypeMirror> predicate(Supplier<TypeMirror> lazy, AnnotationUtils utils, BiFunction<Boolean, String, Boolean> b) {
        return comparer(utils, b, AbsenceAction.PASS_THROUGH).toPredicate(lazy);
    }

    Predicate<TypeMirror> predicate(String name, AnnotationUtils utils, BiFunction<Boolean, String, Boolean> b) {
        return comparer(utils, b, AbsenceAction.PASS_THROUGH).toPredicate(() -> utils.type(name));
    }

    TypeMirrorComparer comparer(AnnotationUtils utils, BiFunction<Boolean, String, Boolean> b) {
        return comparer(utils, b, AbsenceAction.PASS_THROUGH);
    }

    TypeMirrorComparer comparer(AnnotationUtils utils, BiFunction<Boolean, String, Boolean> b, AbsenceAction action) {
        return new TypeMirrorComparer(utils) {
            @Override
            public boolean test(TypeMirror t, TypeMirror u) {
                if (u == null) {
                    if (action != null && action != AbsenceAction.PASS_THROUGH) {
                        return action.getAsBoolean();
                    }
                    return b.apply(false, "No first type provided with " + u);
                }
                if (t == null) {
                    if (action != null && action != AbsenceAction.PASS_THROUGH) {
                        return action.getAsBoolean();
                    }
                    return b.apply(false, "No second type provided with " + t);
                }
                Types types = utils.processingEnv().getTypeUtils();
                boolean result;
                switch (TypeMirrorComparison.this) {
                    case EXACT_MATCH:
                        result = b.apply(t.toString().equals(u.toString()),
                                t + " is does not match " + u);
                        break;
                    case SAME_TYPE:
                        result = b.apply(types.isSameType(t, u), t
                                + " is not the same type as " + u);
                        break;
                    case IS_ASSIGNABLE:
                        result = b.apply(types.isAssignable(t, u), t
                                + " is not assignable as " + u);
                        break;
                    case IS_SUBTYPE:
                        result = b.apply(types.isSubtype(t, u), t
                                + " is not a subtype of " + u);
                        break;
                    case IS_SUPERTYPE:
                        result = b.apply(types.isSubtype(u, t), t
                                + " is not a supertype of " + u);
                        break;
                    case IS_SUBSIGNATURE:
                        if (!(u instanceof ExecutableType)) {
                            return b.apply(false, u
                                    + " is not an executable type");
                        }
                        if (!(t instanceof ExecutableType)) {
                            return b.apply(false, t
                                    + " is not an executable type");
                        }
                        ExecutableType ea = (ExecutableType) t;
                        ExecutableType eb = (ExecutableType) u;
                        result = b.apply(types.isSubsignature(ea, eb), eb
                                + " is not a subsignature of " + ea);
                        break;
                    case IS_SUPERSIGNATURE:
                        if (!(u instanceof ExecutableType)) {
                            return b.apply(false, u
                                    + " is not an executable type");
                        }
                        if (!(t instanceof ExecutableType)) {
                            return b.apply(false, t
                                    + " is not an executable type");
                        }
                        ExecutableType ea1 = (ExecutableType) t;
                        ExecutableType eb1 = (ExecutableType) u;
                        result = b.apply(types.isSubsignature(eb1, ea1), eb1
                                + " is not a supersignature of " + ea1);
                        break;
                    default:
                        throw new AssertionError(TypeMirrorComparison.this);
                }
                return result;
            }

            @Override
            public String toString() {
                return TypeMirrorComparison.this.name();
            }
        };
    }
}

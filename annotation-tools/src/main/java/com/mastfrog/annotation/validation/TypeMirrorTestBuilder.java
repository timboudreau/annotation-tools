package com.mastfrog.annotation.validation;

import com.mastfrog.predicates.NamedPredicate;
import com.mastfrog.predicates.AbsenceAction;
import com.mastfrog.abstractions.AbstractNamed;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;
import javax.lang.model.element.Element;
import javax.lang.model.element.NestingKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.NoType;
import javax.lang.model.type.PrimitiveType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Types;
import com.mastfrog.annotation.AnnotationUtils;
import static com.mastfrog.annotation.AnnotationUtils.simpleName;

/**
 *
 * @author Tim Boudreau
 */
public class TypeMirrorTestBuilder<T> extends AbstractPredicateBuilder<TypeMirror, TypeMirrorTestBuilder<T>, T> {

    public TypeMirrorTestBuilder(AnnotationUtils utils, Function<TypeMirrorTestBuilder<T>, T> converter) {
        super(utils, converter);
    }

    private Supplier<TypeMirror> toTypeMirror(String s) {
        return new TypeMirrorSupplier(s);
    }

    final class TypeMirrorSupplier implements Supplier<TypeMirror> {

        private final String type;

        public TypeMirrorSupplier(String type) {
            this.type = type;
        }

        @Override
        public TypeMirror get() {
            TypeElement el = utils.processingEnv().getElementUtils().getTypeElement(type);
            if (el == null) {
                fail("Could not load type " + type);
                return null;
            }
            return el.asType();
        }

        @Override
        public String toString() {
            return simpleName(type);
        }
    }

    public TypeMirrorTestBuilder<T> isAssignable(Supplier<TypeMirror> other) {
        return addPredicate(() -> "is-assignable-to-" + other,
                TypeMirrorComparison.IS_ASSIGNABLE.comparer(utils, this::maybeFail, AbsenceAction.PASS_THROUGH).toPredicate(other));
    }

    public TypeMirrorTestBuilder<T> isAssignable(String type) {
        return addPredicate(namedPredicate("assignable=" + type, TypeMirrorComparison.IS_ASSIGNABLE.predicate(toTypeMirror(type), utils, this::maybeFail)));
    }

    public TypeMirrorTestBuilder<T> isType(Supplier<TypeMirror> other) {
        return addPredicate("is-same-type-as-" + other, TypeMirrorComparison.SAME_TYPE.comparer(utils, this::maybeFail).toPredicate(other));
    }

    private NamedPredicate<TypeMirror> primitiveTypeTest(String name) {
        // XXX handle array types
        TypeKind kind;
        switch (name) {
            case "byte":
                kind = TypeKind.BYTE;
                break;
            case "short":
                kind = TypeKind.SHORT;
                break;
            case "int":
                kind = TypeKind.INT;
                break;
            case "long":
                kind = TypeKind.LONG;
                break;
            case "boolean":
                kind = TypeKind.BOOLEAN;
                break;
            case "double":
                kind = TypeKind.DOUBLE;
                break;
            case "float":
                kind = TypeKind.FLOAT;
                break;
            case "char":
                kind = TypeKind.CHAR;
                break;
            case "void":
                kind = TypeKind.VOID;
                break;
            default:
                return null;
        }
        return new PrimitiveTypeCheck(name, kind);
    }

    final class PrimitiveTypeCheck extends AbstractNamed implements NamedPredicate<TypeMirror> {

        private final String primitiveName;
        private final TypeKind kind;

        public PrimitiveTypeCheck(String primitiveName, TypeKind kind) {
            this.primitiveName = primitiveName;
            this.kind = kind;
        }

        @Override
        public String name() {
            return "is-" + primitiveName + "(" + kind + ")";
        }

        @Override
        public boolean test(TypeMirror t) {
            Types types = utils.processingEnv().getTypeUtils();
            if (kind == TypeKind.VOID) {
                NoType vt = types.getNoType(kind);
                return types.isSameType(t, vt);
            }
            PrimitiveType primitiveType = types.getPrimitiveType(kind);
            return types.isSameType(t, primitiveType)
                    || types.isSameType(t,
                            types.boxedClass(primitiveType).asType());
        }
    }

    public TypeMirrorTestBuilder<T> isType(String type) {
        NamedPredicate<? super TypeMirror> pred = primitiveTypeTest(type);
        if (pred != null) {
            return addPredicate(pred);
        } else {
            return addPredicate(namedPredicate("is-type=" + type, TypeMirrorComparison.SAME_TYPE.predicate(toTypeMirror(type), utils, this::maybeFail)));
        }
    }

    public TypeMirrorTestBuilder<T> isSubtype(Supplier<TypeMirror> other) {
        return addPredicate(TypeMirrorComparison.IS_SUBTYPE.comparer(utils, this::maybeFail).toPredicate(other));
    }

    public TypeMirrorTestBuilder<T> isSubtype(String type) {
        return addPredicate(namedPredicate("is-subtype=" + type, TypeMirrorComparison.IS_SUBTYPE.predicate(toTypeMirror(type), utils, this::maybeFail)));
    }

    public TypeMirrorTestBuilder<T> isAssignableWithErasure(String type) {
        return addPredicate(namedPredicate("is-assignable-with-erasure=" + type,
                TypeMirrorComparison.IS_ERASURE_ASSIGNABLE.predicate(toTypeMirror(type), utils, this::maybeFail)));
    }

    public TypeMirrorTestBuilder<T> isSubtypeWithErasure(String type) {
        return addPredicate(namedPredicate("is-subtype-with-erasure=" + type,
                TypeMirrorComparison.IS_ERASURE_SUBTYPE.predicate(toTypeMirror(type), utils, this::maybeFail)));
    }

    public TypeMirrorTestBuilder<T> isSupertype(String type) {
        return addPredicate(namedPredicate("is-supertype=" + type, TypeMirrorComparison.IS_SUPERTYPE.predicate(toTypeMirror(type), utils, this::maybeFail)));
    }

    public TypeMirrorTestBuilder<T> isSupertype(Supplier<TypeMirror> other) {
        return addPredicate(TypeMirrorComparison.IS_SUPERTYPE.comparer(utils, this::maybeFail).toPredicate(other));
    }

    public TypeMirrorTestBuilder<T> isSubsignature(String type) {
        return addPredicate(namedPredicate("is-subsignature=" + type, TypeMirrorComparison.IS_SUBSIGNATURE.predicate(toTypeMirror(type), utils, this::maybeFail)));
    }

    public TypeMirrorTestBuilder<T> isSubsignature(Supplier<TypeMirror> other) {
        return addPredicate(TypeMirrorComparison.IS_SUBSIGNATURE.comparer(utils, this::maybeFail).toPredicate(other));
    }

    public TypeMirrorTestBuilder<T> isSupersignature(String type) {
        return addPredicate(namedPredicate("is-supersignature=" + type, TypeMirrorComparison.IS_SUPERSIGNATURE.predicate(toTypeMirror(type), utils, this::maybeFail)));
    }

    public TypeMirrorTestBuilder<T> isSupersignature(Supplier<TypeMirror> other) {
        return addPredicate(TypeMirrorComparison.IS_SUPERSIGNATURE.comparer(utils, this::maybeFail).toPredicate(other));
    }

    public TypeMirrorTestBuilder<T> typeKindMustBe(TypeKind kind) {
        return addPredicate(namedPredicate("require-type-kind=" + kind, m -> {
            return maybeFail(kind == m.getKind(), "Type kind of " + m + " must be " + kind + " but is " + m.getKind());
        }));
    }

    private TypeElement toTypeElement(TypeMirror mir) {
        if (mir instanceof DeclaredType) {
            Element e = ((DeclaredType) mir).asElement();
            if (e instanceof TypeElement) {
                return (TypeElement) e;
            }
        }
        return utils.processingEnv().getElementUtils().getTypeElement(mir.toString());
    }

    public TypeElementTestBuilder<TypeMirrorTestBuilder<T>, ? extends TypeElementTestBuilder<TypeMirrorTestBuilder<T>, ?>> asElement() {
        return (TypeElementTestBuilder /* JDK 8 javac compatibility */) new TypeElementTestBuilder<>(utils, tetb -> {
                    return addPredicate(this::toTypeElement, tetb._predicate());
                });
    }

    public TypeMirrorTestBuilder<T> nestingKindMustBe(NestingKind kind) {
        return addPredicate(namedPredicate("require-nesting=" + kind, tm -> {
            TypeElement el = utils.processingEnv().getElementUtils().getTypeElement(tm.toString());
            return maybeFail(el != null, "Could not find a type element for " + tm, () -> {
                return kind == el.getNestingKind();
            });
        }));
    }

    public TypeMirrorTestBuilder<T> nestingKindMustNotBe(NestingKind kind) {
        return addPredicate(namedPredicate("require-notnesting=" + kind, tm -> {
            TypeElement el = utils.processingEnv().getElementUtils().getTypeElement(tm.toString());
            return maybeFail(el != null, "Could not find a type element for " + tm, () -> {
                return kind != el.getNestingKind();
            });
        }));
    }

    public TypeMirrorTestBuilder<TypeMirrorTestBuilder<T>> testTypeParameterOfSupertypeOrInterface(String supertypeOrInterface, int typeParameter) {
        return (TypeMirrorTestBuilder /* JDK 8 javac compatibility */) new TypeMirrorTestBuilder<>(utils, tmtb -> {
                    typeKindMustBe(TypeKind.DECLARED);
                    return addPredicate(() -> "type-parameter-" + typeParameter + "-of-supertype-or-interface:" + tmtb._predicate().name(), m -> {
                        TypeMirror sup = findSupertypeOrInterfaceOfType(m, supertypeOrInterface);
                        if (sup == null) {
                            return fail("No supertype or interface type matching " + supertypeOrInterface + " on " + m);
                        }
                        if (sup.getKind() != TypeKind.DECLARED) {
                            return fail("Not a declared type: " + sup);
                        }
                        DeclaredType t = (DeclaredType) sup;
                        List<? extends TypeMirror> args = t.getTypeArguments();
                        if (args == null || args.size() < typeParameter) {
                            return fail("No type argument for index " + typeParameter
                                    + " on " + t);
                        }
                        return tmtb.predicate().test(args.get(typeParameter));
                    });
                });
    }

    public TypeMirrorTestBuilder<TypeMirrorTestBuilder<T>> testSupertypeOrInterface(String supertypeOrInterface) {
        return new TypeMirrorTestBuilder<>(utils, tmtb -> {
            typeKindMustBe(TypeKind.DECLARED);
            return addPredicate(() -> "supertype-or-interface", m -> {
                TypeMirror sup = findSupertypeOrInterfaceOfType(m, supertypeOrInterface);
                if (sup == null) {
                    return fail("No supertype or interface type matching "
                            + supertypeOrInterface + " on " + m);
                }
                if (sup.getKind() != TypeKind.DECLARED) {
                    return fail("Not a declared type: " + sup);
                }
                DeclaredType t = (DeclaredType) sup;
                return tmtb.predicate().test(t);
            });
        });
    }

    private TypeMirror findSupertypeOrInterfaceOfType(TypeMirror on, String type) {
        if (type.equals(on.toString()) || type.equals(utils.erasureOf(on))) {
            return on;
        }
        List<? extends TypeMirror> sups = utils.processingEnv().getTypeUtils().directSupertypes(on);
        for (TypeMirror tm : sups) {
            if (type.equals(tm.toString()) || type.equals(utils.erasureOf(tm))) {
                return tm;
            }
        }
        return null;
    }
}

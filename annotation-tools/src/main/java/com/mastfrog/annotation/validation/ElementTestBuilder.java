package com.mastfrog.annotation.validation;

import com.mastfrog.predicates.NamedPredicate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import com.mastfrog.annotation.AnnotationUtils;
import static com.mastfrog.annotation.AnnotationUtils.simpleName;

/**
 *
 * @author Tim Boudreau
 */
public class ElementTestBuilder<E extends Element, R, B extends ElementTestBuilder<E, R, B>>
        extends AbstractPredicateBuilder<E, B, R> {

    ElementTestBuilder(AnnotationUtils utils, Function<B, R> builder) {
        super(utils, builder);
    }

    @SuppressWarnings("unchecked")
    protected B cast() {
        return (B) this;
    }

    public TypeMirrorTestBuilder<B> testElementAsType() {
        return new TypeMirrorTestBuilder<>(utils, tmtb -> {
            return addPredicate("as-type-mirror:" + tmtb._predicate().name(), e -> {
                return tmtb.predicate().test(e.asType());
            });
        });
    }

    public B testElementAsType(Consumer<TypeMirrorTestBuilder<?>> c) {
        boolean[] built = new boolean[1];
        TypeMirrorTestBuilder<Void> b = new TypeMirrorTestBuilder<>(utils, tmtb -> {
            addPredicate("as-type-mirror:" + tmtb._predicate().name(), e -> {
                return tmtb.predicate().test(e.asType());
            });
            built[0] = true;
            return null;
        });
        c.accept(b);
        if (!built[0]) {
            b.build();
        }
        return cast();
    }

    public static <E extends Element, B extends ElementTestBuilder<E, Predicate<? super E>, B>> ElementTestBuilder<E, Predicate<? super E>, B> create(AnnotationUtils utils) {
        return new ElementTestBuilder<>(utils, etb -> {
            return etb.predicate();
        });
    }

    private static <E extends Element, B extends ElementTestBuilder<E, Predicate<? super E>, B>> Function<B, Predicate<? super E>> defaultBuilder() {
        return (tb) -> {
            return tb.predicate();
        };
    }

    public TypeElementTestBuilder<B, ? extends TypeElementTestBuilder<B, ?>> testContainingClass() {
        return new TypeElementTestBuilder<>(utils, tetb -> {
            return addPredicate(() -> "containing-class\n" + tetb._predicate().name(),
                    AnnotationUtils::enclosingType, (et, tp) -> {
                        return tetb.predicate().test(tp);
                    });
        });
    }

    public B testContainingClass(Consumer<TypeElementTestBuilder<B, ? extends TypeElementTestBuilder<B, ?>>> c) {
        boolean[] built = new boolean[1];
        TypeElementTestBuilder<B, ? extends TypeElementTestBuilder<B, ?>> b = new TypeElementTestBuilder<>(utils, tetb -> {
            addPredicate(() -> "containing-class(" + tetb._predicate().name() + ")",
                    AnnotationUtils::enclosingType, (et, tp) -> {
                        return tetb.predicate().test(tp);
                    });
            built[0] = true;
            return null;
        });
        c.accept(b);
        return cast();
    }

    private static List<TypeElement> allEnclosingTypes(Element el) {
        List<TypeElement> result = new ArrayList<>(4);
        Element e = el;
        while (e != null) {
            TypeElement type = AnnotationUtils.enclosingType(e);
            e = type;
            if (type != null) {
                result.add(type);
            }
        }
        return result;
    }

    /**
     * Apply the tests in the returned builder to all containing classes of the
     * one in question.
     *
     * @return A builder
     */
    public TypeElementTestBuilder<B, ? extends TypeElementTestBuilder<B, ?>> testContainingClasses() {
        return new TypeElementTestBuilder<>(utils, tetb -> {
            return addPredicate(() -> "containing-classes\n" + tetb._predicate().name(),
                    el -> {
                        Element e = el;
                        NamedPredicate<TypeElement> pred = tetb._predicate();
                        boolean result = true;
                        while (e != null) {
                            TypeElement type = AnnotationUtils.enclosingType(e);
                            e = type;
                            if (type != null) {
                                result &= pred.test(type);
                            }
                        }
                        return result;
                    });
        });
    }

    public B testContainingClasses(Consumer<TypeElementTestBuilder<?, ?>> c) {
        boolean[] built = new boolean[1];
        TypeElementTestBuilder<?, ? extends TypeElementTestBuilder<?, ?>> b = new TypeElementTestBuilder<>(utils, tetb -> {
            built[0] = true;
            return addPredicate(() -> "containing-classes\n" + tetb._predicate().name(),
                    el -> {
                        Element e = el;
                        NamedPredicate<TypeElement> pred = tetb._predicate();
                        boolean result = true;
                        while (e != null) {
                            TypeElement type = AnnotationUtils.enclosingType(e);
                            e = type;
                            if (type != null) {
                                result &= pred.test(type);
                            }
                        }
                        return result;
                    });
        });
        c.accept(b);
        if (!built[0]) {
            b.build();
        }
        return cast();
    }

    public TypeMirrorTestBuilder<B> testContainingType() {
        return new TypeMirrorTestBuilder<>(utils, tmtb -> {
            return addPredicate(() -> "containing-type(" + tmtb._predicate().name() + ")", AnnotationUtils::enclosingTypeAsTypeMirror, (et, tp) -> {
                if (tp == null) {
                    return true;
                }
                return tmtb.predicate().test(tp);
            });
        });
    }

    public B testContainingType(Consumer<TypeMirrorTestBuilder<?>> c) {
        boolean[] built = new boolean[1];
        TypeMirrorTestBuilder<?> b = new TypeMirrorTestBuilder<>(utils, tmtb -> {
            built[0] = true;
            return addPredicate(() -> "containing-type(" + tmtb._predicate().name() + ")", AnnotationUtils::enclosingTypeAsTypeMirror, (et, tp) -> {
                if (tp == null) {
                    return true;
                }
                return tmtb.predicate().test(tp);
            });
        });
        c.accept(b);
        if (!built[0]) {
            b.build();
        }
        return cast();
    }

    public TypeElementTestBuilder<B, ? extends TypeElementTestBuilder<B, ?>> testOutermostClass() {
        return new TypeElementTestBuilder<>(utils, tetb -> {
            return addPredicate(() -> "outer-compilation-unit-class(" + tetb._predicate().name() + ")", AnnotationUtils::topLevelType, (et, tp) -> {
                return tetb.predicate().test(tp);
            });
        });
    }

    public B testOutermostClass(Consumer<TypeElementTestBuilder<?, ? extends TypeElementTestBuilder<?, ?>>> c) {
        boolean[] built = new boolean[0];
        TypeElementTestBuilder<?, ? extends TypeElementTestBuilder<?, ?>> b = new TypeElementTestBuilder<>(utils, tetb -> {
            addPredicate(() -> "outer-compilation-unit-class(" + tetb._predicate().name() + ")", AnnotationUtils::topLevelType, (et, tp) -> {
                return tetb.predicate().test(tp);
            });
            return null;
        });
        c.accept(b);
        if (!built[0]) {
            b.build();
        }
        return cast();
    }

    public TypeMirrorTestBuilder<B> testOutermostType() {
        return new TypeMirrorTestBuilder<>(utils, tmtb -> {
            return addPredicate(() -> "outer-compilation-unit-type(" + tmtb._predicate().name() + ")", AnnotationUtils::topLevelTypeAsTypeMirror, (et, tp) -> {
                if (tp == null) {
                    return true;
                }
                return tmtb.predicate().test(tp);
            });
        });
    }

    public B testOutermostType(Consumer<TypeMirrorTestBuilder<?>> c) {
        boolean[] built = new boolean[1];
        TypeMirrorTestBuilder<Void> b = new TypeMirrorTestBuilder<>(utils, tmtb -> {
            addPredicate(() -> "outer-compilation-unit-type(" + tmtb._predicate().name() + ")", AnnotationUtils::topLevelTypeAsTypeMirror, (et, tp) -> {
                if (tp == null) {
                    return true;
                }
                return tmtb.predicate().test(tp);
            });
            built[0] = true;
            return null;
        });
        c.accept(b);
        if (!built[0]) {
            b.build();
        }
        return cast();
    }

    Predicate<? super E> getPredicate() {
        return predicate();
    }

    public B mustBeFullyReifiedType() {
        return addPredicate("must-be-fully-reified", (E e) -> {
            return maybeFail(e.asType().getKind() == TypeKind.DECLARED,
                    "Not a fully reified type: " + e.asType());
        });
    }

    public B typeParameterExtends(int ix, String name) {
        return addPredicate("type-param-" + ix + "-must-extends-" + simpleName(name), (e) -> {
            TypeMirror expected = utils.getTypeParameter(ix, e, this::fail);
            TypeElement el = utils.processingEnv().getElementUtils().getTypeElement(name);
            if (el == null) {
                fail("Could not load " + name + " from the classpath");
                return false;
            }
            TypeMirror real = e.asType();
            boolean result = utils.processingEnv().getTypeUtils().isSameType(real, expected) || utils.processingEnv().getTypeUtils().isAssignable(expected, real);
            return maybeFail(result, real + " is not assignable as " + expected);
        });
    }

    public B typeParameterExtendsOneOf(int ix, String name, String... more) {
        if (more.length == 0) {
            return typeParameterExtendsOneOf(ix, name);
        }
        return addPredicate("type-parameter-" + ix + "-extends-one-of-" + name + "," + AnnotationUtils.join(',', more), (e) -> {
            TypeMirror expected = utils.getTypeParameter(ix, e);
            List<String> all = new ArrayList<>();
            all.add(name);
            all.addAll(Arrays.asList(more));
            TypeMirror real = e.asType();
            boolean result = false;
            for (String a : all) {
                TypeElement el = utils.processingEnv().getElementUtils().getTypeElement(name);
                if (el == null) {
                    fail("Could not load " + name + " from the classpath");
                    return false;
                }
                result |= utils.processingEnv().getTypeUtils().isSameType(real, expected) || utils.processingEnv().getTypeUtils().isAssignable(expected, real);
                if (result) {
                    break;
                }
            }
            return maybeFail(result, real + " is not assignable as " + expected);
        });
    }

    public B typeParameterMatches(int ix, BiPredicate<TypeMirror, AnnotationUtils> tester) {
        return addPredicate("type-parameter-" + ix + "-matches-" + tester, (e) -> {
            TypeMirror tm = utils.getTypeParameter(ix, e);
            if (tm == null) {
                fail("No type parameter " + ix);
                return false;
            }
            return tester.test(tm, utils);
        });
    }

    public B isKind(ElementKind kind) {
        return addPredicate("is-element-kind-" + kind, (e) -> {
            if (kind != e.getKind()) {
                fail("Element type must be " + kind + " but is " + e.getKind());
                return false;
            }
            return true;
        });
    }

    public B hasModifier(Modifier m) {
        return addPredicate("must-have-modifier-" + m, (e) -> {
            return maybeFail(e.getModifiers() != null && e.getModifiers().contains(m),
                    "Must be " + m);
        });
    }

    public B hasModifiers(Modifier a, Modifier... more) {
        B result = hasModifier(a);
        for (Modifier m : more) {
            hasModifier(m);
        }
        return result;
    }

    public B doesNotHaveModifier(Modifier m) {
        return addPredicate("must-not-have-modifier-" + m, (e) -> {
            return maybeFail(e.getModifiers() == null || !e.getModifiers().contains(m),
                    "Modifier " + m + " must not be used here");
        });
    }

    public B doesNotHaveModifiers(Modifier a, Modifier... more) {
        doesNotHaveModifier(a);
        for (Modifier m : more) {
            doesNotHaveModifier(m);
        }
        return cast();
    }

    public B isSubTypeOf(String typeName, String... moreTypeNames) {
        String msg;
        if (moreTypeNames.length == 0) {
            msg = "is-subtype-of-" + typeName;
        } else {
            msg = "is-subtype-of-" + typeName + "," + AnnotationUtils.join(',', moreTypeNames);
        }
        return addPredicate(msg, (e) -> {
            if (e == null) {
                return true;
            }
            if (moreTypeNames.length == 0) {
                AnnotationUtils.TypeComparisonResult res = utils.isSubtypeOf(e, typeName);
                if (!res.isSubtype()) {
                    switch (res) {
                        case FALSE:
                            fail("Not a subtype of " + typeName + ": " + e.asType());
                            break;
                        case TYPE_NAME_NOT_RESOLVABLE:
                            fail("Could not resolve on classpath: " + typeName);
                            break;
                    }
                }
                return res.isSubtype();
            } else {
                List<String> all = new ArrayList<>(Arrays.asList(typeName));
                all.addAll(Arrays.asList(moreTypeNames));
                for (String test : all) {
                    AnnotationUtils.TypeComparisonResult res = utils.isSubtypeOf(e, test);
                    if (res.isSubtype()) {
                        return true;
                    }
                }
                fail("Not a subtype of any of " + AnnotationUtils.join(',', all.toArray(new String[0])) + ": " + e.asType());
                return false;
            }
        });
    }
}

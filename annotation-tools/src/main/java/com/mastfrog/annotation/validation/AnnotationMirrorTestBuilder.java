package com.mastfrog.annotation.validation;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import javax.lang.model.element.AnnotationMirror;
import com.mastfrog.annotation.AnnotationUtils;

/**
 *
 * @author Tim Boudreau
 */
public class AnnotationMirrorTestBuilder<T, B extends AnnotationMirrorTestBuilder<T, B>> extends AbstractPredicateBuilder<AnnotationMirror, B, T> {

    public AnnotationMirrorTestBuilder(AnnotationUtils utils, Function<B, T> converter) {
        super(utils, converter);
    }

    public B atLeastOneMemberMayBeSet(String... memberNames) {
        return addPredicate("at-least-one-of-" + AnnotationUtils.join(',', memberNames) + "-must-be-set", (am) -> {
            List<Object> all = new ArrayList<>();
            Set<String> names = new HashSet<>();
            for (String name : memberNames) {
                Object o = utils.annotationValue(am, name, Object.class);
                if (o != null) {
                    names.add(name);
                    all.add(o);
                }
            }
            return maybeFail(!all.isEmpty(), "At least one of "
                    + AnnotationUtils.join(',', memberNames)
                    + " MUST be used but found " + names);
        });
    }

    public B onlyOneMemberMayBeSet(String... memberNames) {
        return addPredicate("no-more-than-one-of-" + AnnotationUtils.join(',', memberNames) + "-may-be-set", (am) -> {
            List<Object> all = new ArrayList<>();
            Set<String> names = new HashSet<>();
            for (String name : memberNames) {
                Object o = utils.annotationValue(am, name, Object.class);
                if (o != null) {
                    names.add(name);
                    all.add(o);
                }
            }
            return maybeFail(all.size() <= 1, "Only one of "
                    + AnnotationUtils.join(',', memberNames)
                    + " may be used, but found "
                    + AnnotationUtils.join(',',
                            names.toArray(new String[names.size()])));
        });
    }

    public B mustBeUnsetIfMemberIsSet(String ifSet, String... mustBeUnset) {
        return addPredicate("if-" + ifSet + "-is-set-"
                + AnnotationUtils.join(',', mustBeUnset) + "-must-not-be-set", (am) -> {
            Object o = utils.annotationValue(am, ifSet, Object.class);
            if (o != null) {

                List<Object> all = new ArrayList<>();
                Set<String> names = new HashSet<>();
                for (String name : mustBeUnset) {
                    Object o1 = utils.annotationValue(am, name, Object.class);
                    if (o1 != null) {
                        names.add(name);
                        all.add(o1);
                    }
                }
                return maybeFail(all.size() <= 1, "If " + ifSet + " is set, "
                        + AnnotationUtils.join(',', mustBeUnset)
                        + " may not be used, "
                        + AnnotationUtils.join(',',
                                names.toArray(new String[names.size()])));
            }
            return true;
        });
    }

    public AnnotationMirrorMemberTestBuilder<B>
            testMember(String memberName) {
        return new AnnotationMirrorMemberTestBuilder<>((ammtb) -> {
            return addPredicate(ammtb._predicate());
        }, memberName, utils);
    }

    @SuppressWarnings("unchecked")
    private B cast() {
        return (B) this;
    }

    public B testMember(String memberName, Consumer<AnnotationMirrorMemberTestBuilder<?>> c) {
        boolean[] built = new boolean[1];
        AnnotationMirrorMemberTestBuilder<Void> m = new AnnotationMirrorMemberTestBuilder<>((ammtb) -> {
            addPredicate(ammtb._predicate());
            built[0] = true;
            return null;
        }, memberName, utils);
        c.accept(m);
        if (!built[0]) {
            m.build();
        }
        return cast();
    }

    public AnnotationMirrorMemberTestBuilder<B> testMemberIfPresent(String memberName) {
        return new AnnotationMirrorMemberTestBuilder<>((ammtb) -> {
            return addPredicate(ammtb._predicate().name(), (outer) -> {
                List<AnnotationMirror> values = utils.annotationValues(outer,
                        memberName, AnnotationMirror.class);
                if (values.isEmpty()) {
                    return true;
                }
                return ammtb.predicate().test(outer);
            });
        }, memberName, utils);
    }

    public B testMemberIfPresent(String memberName, Consumer<AnnotationMirrorMemberTestBuilder<?>> c) {
        boolean[] built = new boolean[1];
        AnnotationMirrorMemberTestBuilder<Void> m = new AnnotationMirrorMemberTestBuilder<>((ammtb) -> {
            addPredicate(ammtb._predicate().name(), (outer) -> {
                List<AnnotationMirror> values = utils.annotationValues(outer,
                        memberName, AnnotationMirror.class);
                if (values.isEmpty()) {
                    return true;
                }
                return ammtb.predicate().test(outer);
            });
            built[0] = true;
            return null;
        }, memberName, utils);
        c.accept(m);
        if (!built[0]) {
            m.build();
        }
        return cast();
    }

    public AnnotationMirrorTestBuilder<B, ? extends AnnotationMirrorTestBuilder<B, ?>> testMemberAsAnnotation(String memberName) {
        return (AnnotationMirrorTestBuilder/* JDK 8 javac compatibility */) new AnnotationMirrorTestBuilder<>(utils, (amtb) -> {
            return addPredicate("member-as-anno-" + memberName + "\n" + amtb._predicate().name(), (AnnotationMirror a) -> {
                List<AnnotationMirror> mir = utils.annotationValues(a, memberName, AnnotationMirror.class);
                Predicate<? super AnnotationMirror> p = amtb.predicate();
                boolean result = true;
                if (p != null) {
                    for (AnnotationMirror am : mir) {
                        result &= p.test(am);
                    }
                }
                return result;
            });
        });
    }

    public B testMemberAsAnnotation(String memberName, Consumer<AnnotationMirrorTestBuilder<?, ? extends AnnotationMirrorTestBuilder<?, ?>>> c) {
        boolean[] built = new boolean[1];
        AnnotationMirrorTestBuilder<Void, ?> res = (AnnotationMirrorTestBuilder/* JDK 8 javac compatibility */)new AnnotationMirrorTestBuilder<>(utils, (amtb) -> {
            addPredicate("member-as-anno-" + memberName + "\n" + amtb._predicate().name(), (AnnotationMirror a) -> {
                List<AnnotationMirror> mir = utils.annotationValues(a, memberName, AnnotationMirror.class);
                Predicate<? super AnnotationMirror> p = amtb.predicate();
                boolean result = true;
                if (p != null) {
                    for (AnnotationMirror am : mir) {
                        result &= p.test(am);
                    }
                }
                return result;
            });
            built[0] = true;
            return null;
        });
        c.accept(res);
        if (!built[0]) {
            res.build();
        }
        return cast();
    }
}

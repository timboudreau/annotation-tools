package com.mastfrog.annotation.validation;

import com.mastfrog.predicates.NamedPredicate;
import com.mastfrog.util.collections.CollectionUtils;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.VariableElement;
import com.mastfrog.annotation.AnnotationUtils;
import static com.mastfrog.annotation.AnnotationUtils.simpleName;

/**
 *
 * @author Tim Boudreau
 */
public class MultiAnnotationTestBuilder {

    private final AnnotationUtils utils;
    private final Map<String, AnnotationPredicateSet> byAnnotation = CollectionUtils.supplierMap(AnnotationPredicateSet::new);

    MultiAnnotationTestBuilder(AnnotationUtils utils) {
        this.utils = utils;
    }

    public static MultiAnnotationTestBuilder createDefault(AnnotationUtils utils) {
        return new MultiAnnotationTestBuilder(utils);
    }

    public BiPredicate<? super AnnotationMirror, ? super Element> build() {
        return new Bip(byAnnotation);
    }

    static final class Bip implements BiPredicate<AnnotationMirror, Element> {

        private final Map<String, AnnotationPredicateSet> byAnnotation = new HashMap<>();

        Bip(Map<? extends String, ? extends AnnotationPredicateSet> m) {
            byAnnotation.putAll(m);
        }

        @Override
        public boolean test(AnnotationMirror t, Element u) {
            AnnotationPredicateSet set = byAnnotation.get(t.getAnnotationType().toString());
            if (set == null) {
                return true;
            }
            return set.test(t, u);
        }

        public String toString() {
            List<String> keys = new ArrayList<>(byAnnotation.keySet());
            Collections.sort(keys, (a, b) -> {
                String a1 = simpleName(a);
                String b1 = simpleName(b);
                return a1.compareTo(b1);
            });
            StringBuilder sb = new StringBuilder();
            for (String key : keys) {
                sb.append(simpleName(key)).append('\n');
                AnnotationPredicateSet set = byAnnotation.get(key);
                set.toString(sb);
            }
            return sb.toString();
        }
    }

    private static class AnnotationPredicateSet implements BiPredicate<AnnotationMirror, Element> {

        private final Map<ElementKind, List<NamedPredicate<Element>>> predicateForElementKind = CollectionUtils.supplierMap(LinkedList::new);
        private final List<NamedPredicate<AnnotationMirror>> annoPredicates = new ArrayList<>(4);
        private final List<Supplier<AnnotationMirrorTestBuilderWithAssociatedElementTests<?, ?>>> suppliers = new ArrayList<>(2);
        private boolean initialized = false;

        void add(Supplier<AnnotationMirrorTestBuilderWithAssociatedElementTests<?, ?>> supp) {
            suppliers.add(supp);
        }

        @Override
        public String toString() {
            return toString(new StringBuilder()).toString();
        }

        private boolean isEmpty() {
            boolean result = annoPredicates.isEmpty();
            if (result) {
                if (!predicateForElementKind.isEmpty()) {
                    for (Map.Entry<ElementKind, List<NamedPredicate<Element>>> e : predicateForElementKind.entrySet()) {
                        result &= e.getValue().isEmpty();
                        if (!result) {
                            break;
                        }
                    }
                }
            }
            return result;
        }

        StringBuilder toString(StringBuilder sb) {
            maybeInit();
            if (isEmpty()) {
                return sb.append("<empty>");
            }
            for (Iterator<NamedPredicate<AnnotationMirror>> it = annoPredicates.iterator(); it.hasNext();) {
                NamedPredicate<AnnotationMirror> pred = it.next();
                sb.append("  ").append(pred.name()).append('\n');
            }
            for (Iterator<Map.Entry<ElementKind, List<NamedPredicate<Element>>>> it = predicateForElementKind.entrySet().iterator(); it.hasNext();) {
                Map.Entry<ElementKind, List<NamedPredicate<Element>>> e = it.next();
                if (!e.getValue().isEmpty()) {
                    sb.append("  ").append(e.getKey()).append('\n');
                    for (NamedPredicate<Element> n : e.getValue()) {
                        sb.append("    ").append(n.name()).append('\n');
                    }
                }
            }
            return sb;
        }

        private void maybeInit() {
            if (!initialized) {
                initialized = true;
                for (Supplier<AnnotationMirrorTestBuilderWithAssociatedElementTests<?, ?>> s : suppliers) {
                    AnnotationMirrorTestBuilderWithAssociatedElementTests<?, ?> b = s.get();
                    annoPredicates.add(b._predicate());
                    b.visitElementPredicates((ElementKind t, List<NamedPredicate<Element>> u) -> {
                        predicateForElementKind.get(t).addAll(u);
                    });
                }
            }
        }

        @Override
        public boolean test(AnnotationMirror am, Element el) {
            maybeInit();
            boolean result = true;
            for (NamedPredicate<AnnotationMirror> pred : annoPredicates) {
                result &= pred.test(am);
                if (!result) {
                    break;
                }
            }
            List<NamedPredicate<Element>> forKind = predicateForElementKind.get(el.getKind());
            for (NamedPredicate<Element> p : forKind) {
                result &= p.test(el);
                if (!result) {
                    break;
                }
            }
            return result;
        }
    }

    public MultiAnnotationTestBuilder whereAnnotationType(String annoType, Consumer<AnnotationMirrorTestBuilderWithAssociatedElementTests<?, ? extends AnnotationMirrorTestBuilderWithAssociatedElementTests>> c) {
        AnnotationPredicateSet set = byAnnotation.get(annoType);
        set.add(() -> {
            boolean[] built = new boolean[1];
            AnnotationMirrorTestBuilderWithAssociatedElementTestsImpl amtb = new AnnotationMirrorTestBuilderWithAssociatedElementTestsImpl(annoType, utils, b -> {
                built[0] = true;
                return null;
            });
            c.accept(amtb);
            if (!built[0]) {
                amtb.build();
            }
            return amtb;
        });
        return this;
    }

    private class AnnotationMirrorTestBuilderWithAssociatedElementTestsImpl extends AnnotationMirrorTestBuilderWithAssociatedElementTests<MultiAnnotationTestBuilder, AnnotationMirrorTestBuilderWithAssociatedElementTestsImpl> {

        private final String annoType;
        private final Map<ElementKind, List<NamedPredicate<Element>>> elementTests = CollectionUtils.supplierMap(ArrayList::new);

        AnnotationMirrorTestBuilderWithAssociatedElementTestsImpl(String annoType, AnnotationUtils utils, Function<AnnotationMirrorTestBuilderWithAssociatedElementTestsImpl, MultiAnnotationTestBuilder> converter) {
            super(utils, converter);
            this.annoType = annoType;
        }

        @Override
        void visitElementPredicates(BiConsumer<ElementKind, List<NamedPredicate<Element>>> bi) {
            elementTests.forEach(bi);
        }

        @SuppressWarnings("unchecked")
        public AnnotationMirrorTestBuilderWithAssociatedElementTestsImpl add(NamedPredicate<? extends Element> p, ElementKind... k) {
            for (ElementKind kind : k) {
                elementTests.get(kind).add((NamedPredicate<Element>) p);
            }
            return this;
        }

        public AnnotationMirrorTestBuilderWithAssociatedElementTestsImpl whereMethodIsAnnotated(Consumer<MethodTestBuilder<?, ? extends MethodTestBuilder<?, ?>>> c) {
            boolean[] built = new boolean[1];
            assert annoType != null;
            MethodTestBuilder<Void, ?> mtb = new MethodTestBuilder<>(utils, b -> {
                add(b._predicate(), ElementKind.METHOD);
                built[0] = true;
                return null;
            });
            c.accept(mtb);
            if (!built[0]) {
                mtb.build();
            }
            return this;
        }

        public AnnotationMirrorTestBuilderWithAssociatedElementTestsImpl whereFieldIsAnnotated(Consumer<ElementTestBuilder<VariableElement, ?, ? extends ElementTestBuilder<VariableElement, ?, ?>>> c) {
            boolean[] built = new boolean[1];
            assert annoType != null;
            ElementTestBuilder<VariableElement, Void, ?> etb = new ElementTestBuilder<>(utils, b -> {
                add(b._predicate(), ElementKind.FIELD);
                built[0] = true;
                return null;
            });
            c.accept(etb);
            if (!built[0]) {
                etb.build();
            }
            return this;
        }

        public AnnotationMirrorTestBuilderWithAssociatedElementTestsImpl whereClassIsAnnotated(Consumer<TypeElementTestBuilder<?, ? extends TypeElementTestBuilder<?, ?>>> c) {
            boolean[] built = new boolean[1];
            assert annoType != null;
            TypeElementTestBuilder<Void, ?> tetb = new TypeElementTestBuilder<>(utils, b -> {
                add(b._predicate(), ElementKind.CLASS);
                add(b._predicate(), ElementKind.INTERFACE);
                built[0] = true;
                return null;
            });
            c.accept(tetb);
            if (!built[0]) {
                tetb.build();
            }
            return this;
        }
    }
}

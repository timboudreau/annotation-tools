package com.mastfrog.annotation.validation;

import com.mastfrog.predicates.NamedPredicate;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.VariableElement;
import com.mastfrog.annotation.AnnotationUtils;

/**
 * Builder provided by {@link MultiAnnotationTestBuilder} which allows
 * for associating method, field or class element validation with an
 * annotation type.
 *
 * @author Tim Boudreau
 */
public abstract class AnnotationMirrorTestBuilderWithAssociatedElementTests<T, B extends AnnotationMirrorTestBuilderWithAssociatedElementTests<T, B>> extends AnnotationMirrorTestBuilder<T, B> {

    AnnotationMirrorTestBuilderWithAssociatedElementTests(AnnotationUtils utils, Function<B, T> converter) {
        super(utils, converter);
    }

    public abstract AnnotationMirrorTestBuilderWithAssociatedElementTests<T, B> whereMethodIsAnnotated(Consumer<MethodTestBuilder<?, ? extends MethodTestBuilder<?, ?>>> c);

    public abstract AnnotationMirrorTestBuilderWithAssociatedElementTests<T, B> whereFieldIsAnnotated(Consumer<ElementTestBuilder<VariableElement, ?, ? extends ElementTestBuilder<VariableElement, ?, ?>>> c);

    public abstract AnnotationMirrorTestBuilderWithAssociatedElementTests<T, B> whereClassIsAnnotated(Consumer<TypeElementTestBuilder<?, ? extends TypeElementTestBuilder<?, ?>>> c);

    abstract void visitElementPredicates(BiConsumer<ElementKind, List<NamedPredicate<Element>>> bi);

}

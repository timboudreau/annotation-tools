package com.mastfrog.annotation.nblayer;

import java.util.function.BiConsumer;
import java.util.function.Function;
import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.Element;
import com.mastfrog.java.vogon.ClassBuilder;
import com.mastfrog.annotation.AnnotationUtils;
import com.mastfrog.annotation.processor.Delegate;
import com.mastfrog.annotation.processor.Delegates;
import com.mastfrog.function.throwing.io.IOBiConsumer;
import com.mastfrog.function.throwing.io.IOConsumer;
import com.mastfrog.util.preconditions.Exceptions;
import java.io.IOException;
import org.openide.filesystems.annotations.LayerBuilder;

/**
 *
 * @author Tim Boudreau
 */
public abstract class LayerGeneratingDelegate extends Delegate {

    private Function<Element[], LayerBuilder> layerBuilderFetcher;
    private BiConsumer<LayerTask, Element[]> layerTaskAdder;

    protected LayerGeneratingDelegate() {
    }

    public final void init(ProcessingEnvironment env, AnnotationUtils utils, IOBiConsumer<ClassBuilder<String>, Element[]> classWriter, Function<Element[], LayerBuilder> layerBuilderFetcher, BiConsumer<LayerTask, Element[]> layerTaskAdder, Delegates del) {
        this.layerBuilderFetcher = layerBuilderFetcher;
        this.layerTaskAdder = layerTaskAdder;
        super.init(env, utils, classWriter, del);
    }

    protected final LayerBuilder layer(Element... elements) {
        return layerBuilderFetcher.apply(elements);
    }

    protected final void addLayerTask(LayerTask task, Element... elements) {
        layerTaskAdder.accept(task, elements);
    }

    protected final void withLayer(IOConsumer<LayerBuilder> c, Element... elements) throws IOException {
        try {
            LayerBuilder lb = layer(elements);
            c.accept(lb);
        } catch (Exception ioe) {
            ioe.printStackTrace(System.err);
            Exceptions.chuck(ioe);
        }
    }
}

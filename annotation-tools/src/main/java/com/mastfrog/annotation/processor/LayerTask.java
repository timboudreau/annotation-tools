package com.mastfrog.annotation.processor;

import org.openide.filesystems.annotations.LayerBuilder;

/**
 *
 * @author Tim Boudreau
 */
public interface LayerTask {

    void run(LayerBuilder bldr) throws Exception;

}

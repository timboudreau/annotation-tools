package com.mastfrog.annotation.processor;

import java.util.Objects;

/**
 *
 * @author Tim Boudreau
 */
public class Key<T> {

    private final String name;
    private final Class<T> type;

    Key(Class<T> type, String name) {
        this.type = type;
        this.name = name;
    }

    Class<T> type() {
        return type;
    }

    @Override
    public String toString() {
        return name + "<" + type.getSimpleName() + ">";
    }

    @Override
    public int hashCode() {
        int hash = 7;
        hash = 79 * hash + Objects.hashCode(this.name);
        hash = 79 * hash + Objects.hashCode(this.type);
        return hash;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }
        final Key<?> other = (Key<?>) obj;
        if (!Objects.equals(this.name, other.name)) {
            return false;
        }
        if (!Objects.equals(this.type, other.type)) {
            return false;
        }
        return true;
    }

}

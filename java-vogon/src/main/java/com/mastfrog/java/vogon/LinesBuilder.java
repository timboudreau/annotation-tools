package com.mastfrog.java.vogon;

import java.util.Arrays;
import java.util.function.Consumer;

/**
 *
 * @author Tim Boudreau
 */
public class LinesBuilder {

    private final StringBuilder sb = new StringBuilder(512);
    private int currIndent;
    private final int indentBy;
    private int maxLineLength = 80;

    LinesBuilder(int indentBy) {
        this.indentBy = indentBy;
    }

    LinesBuilder() {
        this(4);
    }

    public int lineLimit() {
        return maxLineLength;
    }

    private int currLineLength() {
        int pos = sb.length() - 1;
        while (pos >= 0) {
            char c = sb.charAt(pos);
            if (c == '\n' || pos == 0) {
                return sb.length() - pos;
            }
            pos--;
        }
        return 0;
    }

    private char[] newlineIndentChars() {
        char[] c = new char[(currIndent * indentBy) + 1];
        Arrays.fill(c, ' ');
        c[0] = '\n';
        return c;
    }

    private char[] doubleNewlineIndentChars() {
        char[] c = new char[(currIndent * indentBy) + 2];
        Arrays.fill(c, ' ');
        c[0] = '\n';
        c[1] = '\n';
        return c;
    }

    private char[] indent() {
        char[] c = new char[indentBy];
        Arrays.fill(c, ' ');
        return c;
    }

    public LinesBuilder hintLineBreak(int pendingChars) {
        maybeIndent(pendingChars, this.hr);
        return this;
    }

    private void maybeIndent(int pendingChars, boolean hanging) {
        if (currLineLength() + pendingChars > maxLineLength && !isOnNewLine()) {
            sb.append(newlineIndentChars());
            int len = currLineLength();
            for (int i = 1; i < wrapDepth; i++) {
                int nextLen = len + this.indentBy;
                if (nextLen + pendingChars < maxLineLength) {
                    sb.append(this.indent());
                } else {
                    break;
                }
            }
            if (hanging) {
                sb.append(indent());
            }
            if (wrapPrefix != null) {
                sb.append(wrapPrefix);
            }
        }
    }

    private int appendedLength(String sb) {
        int ix = sb.indexOf('\n');
        if (ix >= 0) {
            return ix;
        }
        return sb.length();
    }

    private void maybeWrapFor(String what, boolean hanging) {
        maybeIndent(appendedLength(what), hanging);
    }

    public LinesBuilder newlineIfNewStatement() {
        if (sb.length() == 0) {
            return this;
        }
        int pos = sb.length() - 1;
        loop:
        while (pos > 0) {
            char c = sb.charAt(pos);
            switch (c) {
                case '}':
                case ';':
                    maybeNewline();
                    break loop;
                case ' ':
                case '\r':
                case '\n':
                case '\t':
                    break;
                default:
                    break loop;
            }
            pos--;
        }
        return this;
    }

    public LinesBuilder backup() {
        while (sb.length() > 0 && Character.isWhitespace(sb.charAt(sb.length() - 1))) {
            sb.setLength(sb.length() - 1);
        }
        return this;
    }

    public LinesBuilder switchCase(String on, Consumer<LinesBuilder> c) {
        maybeNewline();
        if (on == null) {
            word("default");
        } else {
            word("case");
            word(on);
        }
        word(":");
        currIndent++;
        try {
            sb.append(newlineIndentChars());
            c.accept(this);
        } finally {
            currIndent--;
        }
        return this;
    }

    public LinesBuilder multiCase(Consumer<LinesBuilder> c, String... cases) {
        if (cases.length == 0) {
            return this;
        }
        maybeNewline();
        for (int i = 0; i < cases.length; i++) {
            String on = cases[i];
            boolean last = i == cases.length - 1;
            if (i > 0) {
                onNewLine();
            }
            if (on == null || "*".equals(on)) {
                word("default");
            } else {
                word("case");
                word(on);
            }
            word(":");
            if (last) {
                currIndent++;
                try {
                    sb.append(newlineIndentChars());
                    c.accept(this);
                } finally {
                    currIndent--;
                }
            }
        }
        return this;
    }

    private String wrapPrefix;

    public char lastNonWhitespaceChar() {
        int pos = sb.length() - 1;
        while (pos >= 0) {
            char c = sb.charAt(pos);
            if (!Character.isWhitespace(c)) {
                return c;
            }
            pos--;
        }
        return 0;
    }

    public LinesBuilder word(String what, char ifNotPrecededBy, boolean hangingWrap) {
        hangingWrap = hangingWrap || inParens;
        char last = lastNonWhitespaceChar();
        if (last == ifNotPrecededBy) {
            return appendRaw(what);
        } else {
            return word(what, hangingWrap);
        }
    }

    public LinesBuilder withWrapPrefix(String pfx, Consumer<LinesBuilder> c) {
        String old = wrapPrefix;
        wrapPrefix = pfx;
        try {
            c.accept(this);
        } finally {
            wrapPrefix = old;
        }
        return this;
    }

    public LinesBuilder word(String what) {
        return word(what, true);
    }

    private boolean hr;

    void hangingWrap(Consumer<LinesBuilder> c) {
        boolean old = hr;
        hr = true;
        c.accept(this);
        hr = old;
    }

    void doubleHangingWrap(Consumer<LinesBuilder> c) {
        boolean old = hr;
        hr = true;
        if (!old) {
            currIndent += 2;
        }
        hangingWrap(c);
        if (!old) {
            currIndent -= 2;
        }
        hr = old;
    }

    private int wrapDepth;

    private boolean inHangingWrap() {
        return hr || wrapDepth > 0;
    }

    public LinesBuilder word(String what, boolean hangingWrap) {
        if (hangingWrap) {
            wrappable(lb -> {
                _word(what);
            });
        } else {
            _word(what);
        }
        return this;
    }

    LinesBuilder _word(String what) {
        if (what == null) {
            throw new NullPointerException("Null word");
        }
        maybeWrapFor(what, inHangingWrap());
        if (sb.length() > 0) {
            char c = sb.charAt(sb.length() - 1);
            if (!Character.isWhitespace(c)) {
                switch (c) {
                    case '[':
                    case '(':
                    case '{':
                    case '<':
                        break;
                    default:
                        sb.append(' ');
                }
            }
        }
        sb.append(what);
        return this;
    }

    public LinesBuilder appendStringLiteral(String literal) {
        sb.append('"').append(escape(literal)).append('"');
        return this;
    }

    public static String stringLiteral(String s) {
        return '"' + escape(s) + '"';
    }

    public static String escape(String literal) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < literal.length(); i++) {
            char c = literal.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\b':
                    sb.append("\\b");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                case '\\':
                    sb.append("\\");
                    break;
                default:
                    sb.append(c);
            }
        }
        return sb.toString();
    }

    public static String escapeCharLiteral(char c) {
        StringBuilder sb = new StringBuilder("'");
        switch (c) {
            case '\\':
                sb.append("\\\\");
                break;
            case '\'':
                sb.append("\\'");
                break;
            case '\t':
                sb.append("\\n");
                break;
            case '\b':
                sb.append("\\b");
                break;
            case '\n':
                sb.append("\\n");
                break;
            default:
                sb.append(c);
        }
        return sb.append('\'').toString();
    }

    public LinesBuilder withoutNewline() {
        while (sb.length() > 0 && Character.isWhitespace(sb.charAt(sb.length() - 1))) {
            sb.setLength(sb.length() - 1);
        }
        while (sb.length() > 0 && sb.charAt(sb.length() - 1) == '\n') {
            sb.setLength(sb.length() - 1);
        }
        return this;
    }

    public LinesBuilder appendRaw(char what) {
        int ix = -1;
        // Ensure commas and semicolons are attached to the
        // thing they delimit
        switch (what) {
            case ',':
            case ';':
                for (int i = sb.length() - 1; i >= 0; i--) {
                    char c = sb.charAt(i);
                    if (Character.isWhitespace(c)) {
                        ix = i;
                    } else if (!Character.isWhitespace(c)) {
                        break;
                    }
                }
        }
        if (ix > 0) {
            sb.insert(ix, what);
        } else {
            sb.append(what);
        }
        return this;
    }

    public LinesBuilder appendRaw(String what) {
        if (what.length() == 1) {
            return appendRaw(what.charAt(0));
        }
        sb.append(what);
        return this;
    }

    public LinesBuilder onNewLine() {
        if (sb.length() == 0) {
            return this;
        }
        boolean didNewLine = maybeNewline();
        if (didNewLine && inHangingWrap()) {
            sb.append(this.indent());
        }
        if (didNewLine && wrapPrefix != null) {
            sb.append(wrapPrefix);
        }
        return this;
    }

    private boolean isOnNewLine() {
        if (sb.length() == 0) {
            return true;
        }
        int sz = sb.length();
        for (int i = sz - 1; i >= 0; i--) {
            char c = sb.charAt(i);
            if (!Character.isWhitespace(c)) {
                break;
            }
            switch (c) {
                case '\n':
                    return true;
            }
        }
        return false;
    }

    public boolean maybeNewline() {
        if (!isOnNewLine()) {
            sb.append(newlineIndentChars());
            return true;
        }
        return false;
    }

    public LinesBuilder statement(String stmt) {
        maybeNewline();
        sb.append(stmt);
        sb.append(';');
        return this;
    }

    private char lastChar() {
        int pos = sb.length() - 1;
        while (pos > 0) {
            char c = sb.charAt(pos);
            if (!Character.isWhitespace(c)) {
                return c;
            }
            pos--;
        }
        return 0;
    }

    public LinesBuilder statement(Consumer<LinesBuilder> c) {
        maybeNewline();
        wrappable(lp -> {
            c.accept(this);
            if (lastChar() != ';') {
                sb.append(';');
            }
        });
        return this;
    }

    int lengthAtWrappableEntry = -1;

    public LinesBuilder wrappable(Consumer<LinesBuilder> c) {
        if (sb.length() == lengthAtWrappableEntry) {
            c.accept(this);
            return this;
        }
        lengthAtWrappableEntry = sb.length();
        int oldWrapDepth = wrapDepth;
        wrapDepth++;
        try {
            c.accept(this);
        } finally {
            wrapDepth = oldWrapDepth;
        }
        lengthAtWrappableEntry = -1;
        return this;
    }

    private boolean inParens;

    public LinesBuilder parens(Consumer<LinesBuilder> c) {
        return delimit('(', ')', lb -> {
            boolean wasInParens = inParens;
            inParens = true;
            c.accept(lb);
            inParens = wasInParens;
        });
    }

    public LinesBuilder delimit(char start, char end, Consumer<LinesBuilder> c) {
        sb.append(start);
        try {
            c.accept(this);
        } finally {
            sb.append(end);
        }
        return this;
    }

    public LinesBuilder squareBrackets(Consumer<LinesBuilder> c) {
        return delimit('[', ']', c);
    }

    public LinesBuilder braces(Consumer<LinesBuilder> c) {
        return delimit('{', '}', c);
    }

    public LinesBuilder commaDelimit(String... all) {
        for (int i = 0; i < all.length; i++) {
            word(all[i]);
            if (i != all.length - 1) {
                sb.append(", ");
            }
        }
        return this;
    }

    public LinesBuilder block(Consumer<LinesBuilder> c) {
        return block(false, c);
    }

    public LinesBuilder indent(Consumer<LinesBuilder> c) {
        // Used for ternary
        currIndent++;
        try {
            c.accept(this);
        } finally {
            currIndent--;
            sb.append(newlineIndentChars());
        }
        return this;
    }

    public LinesBuilder block(boolean leadingNewline, Consumer<LinesBuilder> c) {
        sb.append(" {");
        currIndent++;
        if (leadingNewline) {
            sb.append(doubleNewlineIndentChars());
        } else {
            sb.append(newlineIndentChars());
        }
        try {
            c.accept(this);
        } finally {
            currIndent--;
            maybeNewline();
            int expectedLeadingSpaces = (currIndent * indentBy);
            int realLeadingSpaces = leadingSpaces();
            if (realLeadingSpaces > expectedLeadingSpaces) {
                sb.setLength((sb.length() - (realLeadingSpaces - expectedLeadingSpaces)));
            }
            sb.append('}');
            sb.append(newlineIndentChars());
        }
        return this;
    }

    private int leadingSpaces() {
        int result = 0;
        int pos = sb.length() - 1;
        while (pos > 0) {
            if (' ' == sb.charAt(pos)) {
                result++;
            } else {
                break;
            }
            pos--;
        }
        return result;
    }

    public LinesBuilder newline() {
        if (sb.length() == 0) {
            return this;
        }
        maybeNewline();
        return this;
    }

    public LinesBuilder doubleNewline() {
        if (sb.length() == 0) {
            return this;
        }
        while (sb.length() > 0) {
            if (Character.isWhitespace(sb.charAt(sb.length() - 1))) {
                sb.setLength(sb.length() - 1);
            } else {
                break;
            }
        }
        sb.append(doubleNewlineIndentChars());
        return this;
    }

    @Override
    public String toString() {
        return sb.toString();
    }
}

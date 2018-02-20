package org.embulk.spi.time;

/**
 * RubyTimeFormatToken represents a token in Ruby-compatible time format strings.
 *
 * Embulk's timestamp formats are based on Ruby's formats for historical reasons, and kept for compatibility.
 * Embulk maintains its own implementation of Ruby-compatible time parser to be independent from JRuby.
 *
 * This class is intentionally package-private so that plugins do not directly depend.
 */
abstract class RubyTimeFormatToken {
    abstract boolean isDirective();

    /**
     * Represents flags of a token in Ruby-compatible time format directives.
     *
     * LOCALE_E and LOCALE_O are ignored since they are not used.
     */
    static class Flags {
        private Flags(
                final boolean left,
                final boolean chCase,
                final boolean lower,
                final boolean upper,
                final boolean colons,
                final char padding) {
            this.left = left;
            this.chCase = chCase;
            this.lower = lower;
            this.upper = upper;
            this.colons = colons;
            this.padding = padding;
        }

        static class Builder {
            private Builder() {
                this.left = false;
                this.chCase = false;
                this.lower = false;
                this.upper = false;
                this.colons = false;
                this.padding = '\0';
            }

            Flags build() {
                return new Flags(
                        this.left,
                        this.chCase,
                        this.lower,
                        this.upper,
                        this.colons,
                        this.padding);
            }

            void setLeft(final boolean left) {
                this.left = left;
            }

            void setChCase(final boolean chCase) {
                this.chCase = chCase;
            }

            void setLower(final boolean lower) {
                this.lower = lower;
            }

            void setUpper(final boolean upper) {
                this.upper = upper;
            }

            void setColons(final boolean colons) {
                this.colons = colons;
            }

            void setPadding(final char padding) {
                this.padding = padding;
            }

            private boolean left;
            private boolean chCase;
            private boolean lower;
            private boolean upper;
            private boolean colons;
            private char padding;
        }

        static Builder builder() {
            return new Builder();
        }

        boolean isLeft() {
            return this.left;
        }

        boolean isChCase() {
            return this.chCase;
        }

        boolean isLower() {
            return this.lower;
        }

        boolean isUpper() {
            return this.upper;
        }

        boolean isColons() {
            return this.colons;
        }

        char getPadding() {
            return this.padding;
        }

        @Override
        public String toString() {
            final StringBuilder builder = new StringBuilder();

            if (this.left) {
                builder.append('-');
            }

            if (this.upper) {
                builder.append('^');
            }

            if (this.chCase) {
                builder.append('#');
            }

            if (this.padding == ' ') {
                builder.append('_');
            } else if (this.padding == '0') {
                builder.append('0');
            }

            return builder.toString();
        }

        @Override
        public boolean equals(final Object otherObject) {
            if (!(otherObject instanceof Flags)) {
                return false;
            }
            final Flags other = (Flags) otherObject;
            return this.left == other.left
                           && this.chCase == other.chCase
                           && this.lower == other.lower
                           && this.upper == other.upper
                           && this.colons == other.colons
                           && this.padding == other.padding;
        }

        final boolean left;
        final boolean chCase;
        final boolean lower;
        final boolean upper;
        final boolean colons;
        final char padding;
    }

    abstract static class Directive extends RubyTimeFormatToken {
        Directive(final RubyTimeFormatDirective formatDirective) {
            this.formatDirective = formatDirective;
        }

        @Override
        final boolean isDirective() {
            return true;
        }

        final RubyTimeFormatDirective getFormatDirective() {
            return this.formatDirective;
        }

        final RubyTimeFormatDirective formatDirective;
    }

    static class SimpleDirective extends Directive {
        SimpleDirective(final RubyTimeFormatDirective formatDirective) {
            super(formatDirective);
        }

        @Override
        public boolean equals(final Object otherObject) {
            if (!(otherObject instanceof SimpleDirective)) {
                return false;
            }
            final SimpleDirective other = (SimpleDirective) otherObject;
            return this.formatDirective.equals(other.formatDirective);
        }

        @Override
        public String toString() {
            return "<%" + this.formatDirective.toString() + ">";
        }
    }

    static class ComplexDirective extends Directive {
        ComplexDirective(final RubyTimeFormatDirective formatDirective,
                         final Flags flags,
                         final int precision) {
            super(formatDirective);
            this.flags = flags;
            this.precision = precision;
        }

        @Override
        public boolean equals(final Object otherObject) {
            if (!(otherObject instanceof ComplexDirective)) {
                return false;
            }
            final ComplexDirective other = (ComplexDirective) otherObject;
            return this.formatDirective.equals(other.formatDirective)
                           && this.precision == other.precision
                           && this.flags.equals(other.flags);
        }

        @Override
        public String toString() {
            return "<%"
                           + this.flags.toString()
                           + (this.precision == 0 ? "" : Integer.toString(this.precision))
                           + this.formatDirective.toString()
                           + ">";
        }

        private final Flags flags;
        private final int precision;
    }

    static class Immediate extends RubyTimeFormatToken {
        Immediate(final char character) {
            this.string = "" + character;
        }

        Immediate(final String string) {
            this.string = string;
        }

        @Override
        public boolean equals(final Object otherObject) {
            if (!(otherObject instanceof Immediate)) {
                return false;
            }
            final Immediate other = (Immediate) otherObject;
            return this.string.equals(other.string);
        }

        @Override
        public String toString() {
            return "<\"" + this.string + "\">";
        }

        @Override
        boolean isDirective() {
            return false;
        }

        String getContent() {
            return this.string;
        }

        private final String string;
    }
}

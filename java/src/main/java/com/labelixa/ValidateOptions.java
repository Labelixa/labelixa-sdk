package com.labelixa;

/**
 * Options for {@link LabelixaClient#validate(String, ValidateOptions)}:
 * the linter to run and, for ZPL, the label geometry the checks assume.
 */
public final class ValidateOptions {

    String language = "zpl";
    int dpmm = 8;
    double widthIn = 4;
    double heightIn = 6;

    /** Creates options with the defaults: ZPL, 8 dpmm, 4x6 inches. */
    public ValidateOptions() {
    }

    /**
     * Selects the linter: {@code zpl} (default), {@code epl}, {@code tspl}
     * or {@code cpcl}.
     *
     * @param language the language name
     * @return this
     */
    public ValidateOptions language(String language) {
        this.language = language;
        return this;
    }

    /**
     * Sets the print density the ZPL checks assume.
     *
     * @param dpmm the density
     * @return this
     */
    public ValidateOptions dpmm(int dpmm) {
        this.dpmm = dpmm;
        return this;
    }

    /**
     * Sets the label size the ZPL checks assume, in inches.
     *
     * @param widthIn width in inches
     * @param heightIn height in inches
     * @return this
     */
    public ValidateOptions size(double widthIn, double heightIn) {
        this.widthIn = widthIn;
        this.heightIn = heightIn;
        return this;
    }
}

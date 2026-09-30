package com.labelixa;

/**
 * Density, size and which label to draw. Defaults: ZPL, 8 dpmm, 4x6
 * inches, first label, no rotation.
 *
 * <p>Density and size apply to ZPL; for EPL, TSPL and CPCL the size comes
 * from the code itself and only {@link #index(int)} applies.
 *
 * <pre>{@code
 * client.renderPng(zpl, new RenderOptions().dpmm(12).size(2.25, 4));
 * }</pre>
 */
public final class RenderOptions {

    String language = "zpl";
    int dpmm = 8;
    double widthIn = 4;
    double heightIn = 6;
    int index = 0;
    int rotation = 0;

    /** Creates options with the defaults. */
    public RenderOptions() {
    }

    /**
     * Selects the printer language: {@code zpl} (default), {@code epl},
     * {@code tspl} or {@code cpcl}.
     *
     * @param language the language name
     * @return this
     */
    public RenderOptions language(String language) {
        this.language = language;
        return this;
    }

    /**
     * Sets the print density in dots per millimetre (6, 8, 12 or 24).
     *
     * @param dpmm the density
     * @return this
     */
    public RenderOptions dpmm(int dpmm) {
        this.dpmm = dpmm;
        return this;
    }

    /**
     * Sets the label size in inches.
     *
     * @param widthIn width in inches
     * @param heightIn height in inches
     * @return this
     */
    public RenderOptions size(double widthIn, double heightIn) {
        this.widthIn = widthIn;
        this.heightIn = heightIn;
        return this;
    }

    /**
     * Selects which label of a multi-label document to draw (0-based).
     *
     * @param index the label index
     * @return this
     */
    public RenderOptions index(int index) {
        this.index = index;
        return this;
    }

    /**
     * Rotates the rendered ZPL label by 0, 90, 180 or 270 degrees.
     *
     * @param degrees the rotation
     * @return this
     */
    public RenderOptions rotation(int degrees) {
        this.rotation = degrees;
        return this;
    }
}

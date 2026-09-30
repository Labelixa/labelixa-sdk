package com.labelixa;

/**
 * Options for {@link LabelixaClient#renderPdf(String, PdfOptions)}.
 *
 * <p>{@link #allLabels(boolean)} puts every label of the code in one PDF.
 * That costs one quota unit PER LABEL (a single page costs one), a server
 * rule which this client states rather than softens.
 */
public final class PdfOptions {

    int dpmm = 8;
    double widthIn = 4;
    double heightIn = 6;
    int index = 0;
    boolean allLabels = false;

    /** Creates options with the defaults: 8 dpmm, 4x6 inches, first label. */
    public PdfOptions() {
    }

    /**
     * Sets the print density in dots per millimetre.
     *
     * @param dpmm the density
     * @return this
     */
    public PdfOptions dpmm(int dpmm) {
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
    public PdfOptions size(double widthIn, double heightIn) {
        this.widthIn = widthIn;
        this.heightIn = heightIn;
        return this;
    }

    /**
     * Selects which label to render (0-based); ignored when
     * {@link #allLabels(boolean)} is on.
     *
     * @param index the label index
     * @return this
     */
    public PdfOptions index(int index) {
        this.index = index;
        return this;
    }

    /**
     * Renders every label of the document into one PDF, one page per
     * label. Costs one quota unit per label.
     *
     * @param all {@code true} for the whole document
     * @return this
     */
    public PdfOptions allLabels(boolean all) {
        this.allLabels = all;
        return this;
    }
}

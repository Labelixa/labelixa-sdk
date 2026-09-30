package com.labelixa;

/**
 * Options for {@link LabelixaClient#barcode(String, BarcodeOptions)}: the
 * symbology and the output format.
 */
public final class BarcodeOptions {

    String type = "code128";
    String format = "svg";

    /** Creates options with the defaults: Code 128 as SVG. */
    public BarcodeOptions() {
    }

    /**
     * Selects the symbology, for example {@code code128}, {@code qr},
     * {@code ean13} or {@code datamatrix}.
     *
     * @param type the symbology name
     * @return this
     */
    public BarcodeOptions type(String type) {
        this.type = type;
        return this;
    }

    /**
     * Selects the output format: {@code svg} (default) or {@code png}.
     *
     * @param format the format
     * @return this
     */
    public BarcodeOptions format(String format) {
        this.format = format;
        return this;
    }
}

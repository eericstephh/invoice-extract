package com.invoiceextract.desktop.data.document

/**
 * The invoice file formats the desktop front-end accepts, in one place.
 *
 * Three layers consult this — the document stage that dispatches on it, the view model
 * gate that rejects anything else before the pipeline starts, and the native entry
 * points (file dialog, drop target) that filter on it. Defining the set once, here,
 * is what keeps those three from drifting apart the next time a format is added:
 * extending support means touching this object, not hunting three call sites.
 */
internal object InvoiceFileFormats {

    /** Digital invoices with an embedded text layer: the PDFBox fast path. */
    const val PDF = "pdf"

    /** Photographed or scanned invoices: validated with `ImageIO`, then OCR-gated. */
    val IMAGES = setOf("png", "jpg", "jpeg")

    /** Every extension the window accepts, for dialogs, drops and gates. */
    val ALL = setOf(PDF) + IMAGES

    /** True for `pdf`, `png`, `jpg` and `jpeg`, case-insensitively. */
    fun isSupported(extension: String): Boolean =
        ALL.any { it.equals(extension, ignoreCase = true) }

    /** True for the image extensions only. */
    fun isImage(extension: String): Boolean =
        IMAGES.any { it.equals(extension, ignoreCase = true) }
}

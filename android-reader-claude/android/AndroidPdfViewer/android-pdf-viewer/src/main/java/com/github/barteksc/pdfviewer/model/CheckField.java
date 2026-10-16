package com.github.barteksc.pdfviewer.model;

/** One check box or radio button on a page: its annotation index, whether it is ticked, and its field name. */
public final class CheckField {
    public final int page;
    public final int annotationIndex;
    public final boolean checked;
    public final String name;

    public CheckField(int page, int annotationIndex, boolean checked, String name) {
        this.page = page;
        this.annotationIndex = annotationIndex;
        this.checked = checked;
        this.name = name;
    }
}
